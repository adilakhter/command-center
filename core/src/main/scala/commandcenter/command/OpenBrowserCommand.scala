package commandcenter.command

import commandcenter.CCRuntime.Env
import commandcenter.command.CommandError._
import commandcenter.util.ProcessUtil
import io.circe.Decoder
import zio.{ IO, UIO, ZIO }

final case class OpenBrowserCommand() extends Command[Unit] {
  val commandType: CommandType = CommandType.OpenBrowserCommand

  val commandNames: List[String] = List.empty

  val title: String = "Open in Browser"

  def preview(searchInput: SearchInput): ZIO[Env, CommandError, List[PreviewResult[Unit]]] = {
    val input      = searchInput.input
    val startsWith = input.startsWith("http://") || input.startsWith("https://")

    // TODO: also check endsWith TLD + URL.isValid

    if (startsWith) {
      UIO(List(Preview.unit.onRun(ProcessUtil.openBrowser(input))))
    } else {
      IO.fail(NotApplicable)
    }
  }
}

object OpenBrowserCommand extends CommandPlugin[OpenBrowserCommand] {
  implicit val decoder: Decoder[OpenBrowserCommand] = Decoder.const(OpenBrowserCommand())
}
