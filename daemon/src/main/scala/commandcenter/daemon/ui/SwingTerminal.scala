package commandcenter.daemon.ui

import java.awt.event.{ KeyAdapter, KeyEvent }
import java.awt.{ BorderLayout, Color, Dimension, Font, GraphicsEnvironment }

import commandcenter.CCRuntime.Env
import commandcenter.command.{ Command, CommandResult, PreviewResult, SearchResults }
import commandcenter.ui.{ CCProcess, CCTheme }
import commandcenter.util.{ Debounced, OS }
import commandcenter.view.AnsiRendered
import commandcenter.{ CCConfig, CCRuntime, CCTerminal, TerminalType }
import javax.swing._
import javax.swing.event.{ DocumentEvent, DocumentListener }
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.text.{ DefaultStyledDocument, StyleConstants, StyleContext }
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._

import scala.annotation.tailrec

final case class SwingTerminal(
  var config: CCConfig, // TODO: Convert to Ref
  ccProcess: CCProcess,
  commandCursorRef: Ref[Int],
  searchResultsRef: Ref[SearchResults[Any]],
  searchDebounce: URIO[Env, Unit] => URIO[Env with Clock, Fiber[Nothing, Unit]]
)(runtime: CCRuntime)
    extends CCTerminal {
  val terminalType: TerminalType = TerminalType.Swing

  val theme    = CCTheme.default
  val document = new DefaultStyledDocument
  val context  = new StyleContext
  val frame    = new JFrame("Command Center")
  val font     = filterMissingFonts(config.display.fonts).headOption.getOrElse(new Font("Monospaced", Font.PLAIN, 18))

  frame.setBackground(theme.background)
  frame.setUndecorated(true)
  frame.setOpacity(config.display.opacity)
  frame.getContentPane.setLayout(new BorderLayout())

  val inputTextField = new JTextField()
  inputTextField.setFont(font)
  inputTextField.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10))
  inputTextField.setBackground(theme.background)
  inputTextField.setForeground(theme.foreground)
  inputTextField.setCaretColor(theme.foreground)
  frame.getContentPane.add(inputTextField, BorderLayout.NORTH)

  val outputTextPane = new JTextPane(document)
  outputTextPane.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 10))
  outputTextPane.setFont(font)
  outputTextPane.setBackground(theme.background)
  outputTextPane.setForeground(theme.foreground)
//  outputTextPane.setCaretColor(Color.RED)
  outputTextPane.setEditable(false)

  // To prevent auto-scrolling to the bottom of the text pane
//  outputTextPane.getCaret.asInstanceOf[DefaultCaret].setUpdatePolicy(DefaultCaret.NEVER_UPDATE)

  val outputScrollPane = new JScrollPane(
    outputTextPane,
    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
  ) {
    override def getPreferredSize: Dimension = {
      val height = if (runtime.unsafeRun(searchResultsRef.get).results.isEmpty) {
        0
      } else {
        outputTextPane.getPreferredSize.height min config.display.maxHeight
      }

      new Dimension(config.display.width, height)
    }
  }
  outputScrollPane.setBorder(BorderFactory.createEmptyBorder())

  outputScrollPane.getVerticalScrollBar.setUI(new BasicScrollBarUI() {
    val emptyButton: JButton = {
      val button = new JButton()
      button.setPreferredSize(new Dimension(0, 0))
      button.setMinimumSize(new Dimension(0, 0))
      button.setMaximumSize(new Dimension(0, 0))
      button
    }

    override def createDecreaseButton(orientation: Int): JButton = emptyButton
    override def createIncreaseButton(orientation: Int): JButton = emptyButton

    override protected def configureScrollBarColors(): Unit = {
      thumbColor = new Color(50, 50, 50)
      thumbDarkShadowColor = new Color(30, 30, 30)
      thumbHighlightColor = new Color(90, 90, 90)
      thumbLightShadowColor = new Color(70, 70, 70)
      trackColor = Color.BLACK
      trackHighlightColor = Color.LIGHT_GRAY
    }
  })

  frame.getContentPane.add(outputScrollPane, BorderLayout.CENTER)

  inputTextField.getDocument.addDocumentListener(new DocumentListener {
    def onChange(e: DocumentEvent): Unit = {
      val searchTerm = inputTextField.getText

      runtime.unsafeRunAsync_ {
        searchDebounce(
          Command
            .search(config.commands, config.aliases, searchTerm, SwingTerminal.this)
            .tap(r => commandCursorRef.set(0) *> searchResultsRef.set(r) *> render(r))
            .unit
        ).flatMap(_.join)
      }
    }

    override def insertUpdate(e: DocumentEvent): Unit  = onChange(e)
    override def removeUpdate(e: DocumentEvent): Unit  = onChange(e)
    override def changedUpdate(e: DocumentEvent): Unit = onChange(e)
  })

  private def render(searchResults: SearchResults[Any]): UIO[Unit] =
    for {
      commandCursor <- commandCursorRef.get
    } yield {
      SwingUtilities.invokeLater { () =>
        def colorMask(width: Int): Long = ~0L >>> (64 - width)

        document.remove(0, document.getLength)

        var scrollToPosition: Int = 0

        val str = searchResults.rendered.zipWithIndex.map {
          case (r, i) =>
            r match {
              case ar: AnsiRendered =>
                val bar = if (i == commandCursor) {
                  fansi.Back.Green(" ")
                } else {
                  fansi.Back.DarkGray(" ")
                }

                if (i < commandCursor) {
                  scrollToPosition += ar.ansiStr.length + 3
                }

                bar ++ fansi.Str(" ") ++ ar.ansiStr
            }
        }.reduceOption(_ ++ fansi.Str("\n") ++ _).getOrElse(fansi.Str(""))

        var i: Int = 0
        groupConsecutive(str.getColors.toList).foreach { c =>
          val s = str.plainText.substring(i, i + c.length)

          i += c.length

          val ansiForeground = (c.head >>> fansi.Color.offset) & colorMask(fansi.Color.width)
          val ansiBackground = (c.head >>> fansi.Back.offset) & colorMask(fansi.Back.width)

          val awtForeground = CCTheme.default.fromFansiColorCode(ansiForeground.toInt)
          val awtBackground = CCTheme.default.fromFansiColorCode(ansiBackground.toInt)

          val style = context.addStyle(ansiForeground.toString, null)
          awtForeground.foreach(StyleConstants.setForeground(style, _))
          awtBackground.foreach(StyleConstants.setBackground(style, _))

          document.insertString(document.getLength, s, style)
        }

        outputTextPane.setCaretPosition(scrollToPosition)

        frame.pack()
      }
    }

  def reset(): UIO[Unit] =
    for {
      _ <- commandCursorRef.set(0)
      _ <- UIO(inputTextField.setText(""))
      _ <- UIO(document.remove(0, document.getLength))
      _ <- searchResultsRef.set(SearchResults.empty)
    } yield ()

  def runSelected(results: SearchResults[Any], cursorIndex: Int): RIO[Env, Option[PreviewResult[Any]]] =
    for {
      previewResult <- ZIO.fromOption(results.results.lift(cursorIndex)).option
      _             <- ZIO.fromOption(previewResult).flatMap(_.onRun).either.forkDaemon
      _             <- reset()
    } yield previewResult

  // TODO: Add ZKeyListener that has unsafeRunAsync_ baked in
  inputTextField.addKeyListener(new KeyAdapter {
    override def keyPressed(e: KeyEvent): Unit =
      e.getKeyCode match {
        case KeyEvent.VK_ENTER =>
          frame.setVisible(false)

          runtime.unsafeRunAsync_ {
            for {
              previousResults <- searchResultsRef.get
              cursorIndex     <- commandCursorRef.get
              x               <- runSelected(previousResults, cursorIndex)
              _ = if (x.map(_.result).contains(CommandResult.Exit)) {
                System.exit(0)
//              SwingUtilities.invokeLater(() => frame.dispose())
              }
            } yield ()
          }

        case KeyEvent.VK_ESCAPE =>
          frame.setVisible(false)

          runtime.unsafeRunAsync_ {
            deactivate *> reset()
          }

        case KeyEvent.VK_DOWN =>
          e.consume()

          runtime.unsafeRunAsync_ {
            for {
              previousResults <- searchResultsRef.get
              _               <- commandCursorRef.update(cursor => (cursor + 1) min (previousResults.results.length - 1))
              _               <- render(previousResults)
            } yield ()
          }

        case KeyEvent.VK_UP =>
          e.consume()

          runtime.unsafeRunAsync_ {
            for {
              previousResults <- searchResultsRef.get
              _               <- commandCursorRef.update(cursor => (cursor - 1) max 0)
              _               <- render(previousResults)
            } yield ()
          }

        case _ =>
      }
  })

  frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
  frame.setLocation(1000, 100)
  frame.setMinimumSize(new Dimension(config.display.width, 20))
  frame.pack()

  def clearScreen: UIO[Unit] = UIO {
    document.remove(0, document.getLength)
  }

  def open: Task[Unit] = Task {
    val bounds =
      GraphicsEnvironment.getLocalGraphicsEnvironment.getDefaultScreenDevice.getDefaultConfiguration.getBounds

    val x = (bounds.width - frame.getWidth) / 2

    frame.setLocation(x, 0)
    frame.setVisible(true)
  }

  def hide: UIO[Unit] = UIO(frame.setVisible(false))

  def activate: RIO[Blocking, Unit] = OS.os match {
    case OS.MacOS => ccProcess.activate
    case _        => UIO(frame.requestFocus())
  }

  def deactivate: RIO[Blocking, Unit] = OS.os match {
    case OS.MacOS => ccProcess.hide
    case _        => UIO.unit
  }

  def opacity: RIO[Env, Float] = UIO(frame.getOpacity)

  def setOpacity(opacity: Float): RIO[Env, Unit] = Task(frame.setOpacity(opacity))

  def size: RIO[Env, Dimension] = UIO(frame.getSize)

  def setSize(width: Int, maxHeight: Int): RIO[Env, Unit] =
    UIO {
      config = config.copy(
        display = config.display.copy(width = width, maxHeight = maxHeight)
      )
    }

  def reload: RIO[Env, Unit] =
    for {
      newConfig <- CCConfig.load
    } yield {
      config = newConfig
    }

  private def filterMissingFonts(fonts: List[Font]): List[Font] = {
    val installedFontNames = GraphicsEnvironment.getLocalGraphicsEnvironment.getAvailableFontFamilyNames.toSet

    fonts.filter(f => installedFontNames.contains(f.getName))
  }

  @tailrec
  private def groupConsecutive[A](list: List[A], acc: List[List[A]] = Nil): List[List[A]] = list match {
    case head :: tail =>
      val (t1, t2) = tail.span(_ == head)
      groupConsecutive(t2, acc :+ (head :: t1))
    case _ => acc
  }
}

object SwingTerminal {
  def create(config: CCConfig, runtime: CCRuntime): Managed[Throwable, SwingTerminal] =
    for {
      searchDebounce   <- Debounced[Env, Nothing, Unit](250.millis).toManaged_
      process          <- CCProcess.get.toManaged_
      commandCursorRef <- Ref.make(0).toManaged_
      searchResultsRef <- Ref.make(SearchResults.empty[Any]).toManaged_
    } yield new SwingTerminal(config, process, commandCursorRef, searchResultsRef, searchDebounce)(runtime)
}