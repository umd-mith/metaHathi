import _root_.akka.actor.{Props, ActorSystem}
import org.mith.metaHathi._
import org.scalatra._
import javax.servlet.ServletContext

class ScalatraBootstrap extends LifeCycle {

  val system = ActorSystem()

  override def init(context: ServletContext) {
    context.mount(new HathiImport(system), "/*")
  }
}
