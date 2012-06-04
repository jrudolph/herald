package herald

import dispatch._
import java.net.URI
import java.io.{File,FileInputStream}
import org.streum.configrity._
import scala.util.control.Exception.allCatch
import xml.{XML, NodeSeq, Node}
import org.pegdown.PegDownProcessor

object Herald {
  def heraldCredentialsPath = 
    file(new File(System.getProperty("user.home")), ".herald")

  def heraldProperties =
    for (p <- heraldCredentialsPath.right)
      yield Configuration.load(p.getPath)

  def heraldProperty(name: String) =
    for {
      path <- heraldCredentialsPath.right
      props <- heraldProperties.right
      prop <- props.get[String](name).toRight {
        "Required property %s not found in file %s".format(
          name, path
        )
      }.right
    } yield prop

  def accessToken = 
    for {
      token <- heraldProperty("oauth_token").right
      secret <- heraldProperty("oauth_token_secret").right
    } yield new com.ning.http.client.oauth.RequestToken(token,secret)

  /** Create file if it doesn't exist */
  def file(parent: File, child: String) =
    allCatch.opt { 
      val f = new File(parent, child)
      f.createNewFile()
      f
    }.toRight {
      "Config file %s/%s can't be created".format(parent.toString, child)
    }

  def dir(parent: File, child: String) =
    file(parent, child).right.flatMap { f =>
      if (f.isDirectory) Right(f)
      else Left("Path %s must be a directory".format(f))
    }

  def notesFile(name: String) =
    notesDirectory.right.flatMap { d =>
      file(d, "%s.%s".format(name, notesExtension))
    }
  def adminEmail = "nathan@technically.us"
  def tumblrName = "implicitly-notes"
  /** Tumblr host name for notes.implicit.ly */
  def tumblrHostname = tumblrName + ".tumblr.com"

  def base = new File(".").getCanonicalFile

  /** Title defaults to name and version */
  def title = version.right.map { v => "%s %s".format(name, v) }
  def name = base.getName

  /** Path to release notes and text about project. */
  def notesDirectory = dir(base, "notes")

  implicit def seqOrdering[A](implicit cmp: Ordering[A]) =
    new Ordering[Seq[A]] {
      @annotation.tailrec
      def compare (lx: Seq[A], ly: Seq[A]) = {
        if (lx.isEmpty && ly.isEmpty) 0
        else if (lx.isEmpty) -1
        else if (ly.isEmpty) 1
        else {
          val cur = cmp.compare(lx.head, ly.head)
          if (cur == 0) compare(lx.tail, ly.tail)
          else cur
        }
      }
    }

  def version: Either[String,String] =
    notesDirectory.right.flatMap { notes =>
      val Note = ("(.*)[.]" + notesExtension).r
      val versions = notes.listFiles.map { _.getName }.collect {
        case Note(version) if version != aboutName => version
      }
      if (versions.isEmpty)
        Left("no version notes found in " + notes)
      else Right(
        versions.maxBy {
          _.split("\\D").filterNot { _.isEmpty }.map { _.toInt }.toSeq
        }
      )
    }

  def versionFile = for {
    notes <- notesDirectory.right
    v <- version.right
    f <- notesFile(v).right
  } yield f


  def aboutName = "about"
  /** Project info named about.markdown. */
  def aboutFile = notesDirectory.right.flatMap { n =>
    notesFile(aboutName)
  }
  def notesExtension = "markdown"

  /** The content to be posted, transformed into xml. Default impl
   *  is the version notes followed by the "about" boilerplate in a
   *  div of class "about" */
  def bodyContent =
    for {
      versionNotes <- versionFile.right
      about <- aboutFile.right
    } yield
      mdToXml(versionNotes) ++
        <div class="about"> { mdToXml(about) } </div>

  val pegDown = new PegDownProcessor
  /** @return node sequence from str or Nil if str is null or empty. */
  private def mdToXml(str: String): NodeSeq = str match {
    case null | "" => Nil
    case _ => XML.loadString("<wrapper>"+pegDown.markdownToHtml(str)+"</wrapper>").iterator.toSeq
  }   

  /** @return node sequence from file or Nil if file is not found. */
  private def mdToXml(md: File): NodeSeq =
    if (md.exists)
      mdToXml(scala.io.Source.fromFile(md).mkString)
    else
      Nil

  def run(args: Array[String]) = {
    val either = args match {
      case Array("--publish") =>
        val promised = for {
          body <- Promise(bodyContent).right
          title <- Promise(title).right
          token <- Promise(accessToken).right
//          _ <- Publish.duplicate(email, pass, site, title).right
          url <- Publish(body, token, tumblrHostname, title, name).right
        } yield {
          unfiltered.util.Browser.open(url)
          "Published %s\n-> %s".format(title, url)
        }
        promised()
      case Array("--version") =>
        Right("herald is ready to go")
      case _ =>
        for {
          _ <- bodyContent.right
          _ <- title.right
        } yield {
          Preview(bodyContent, title)
          "Stopped preview."
        }
    }
    Http.shutdown()
    either.fold(
      err => { System.err.println(err); 1 },
      msg => { println(msg); 0 }
    )
  }
  def main(args: Array[String]) {
    System.exit(run(args))
  }
}

class Herald extends xsbti.AppMain {
  def run(config: xsbti.AppConfiguration) = {
    Exit(Herald.run(config.arguments))
  }
  case class Exit(val code: Int) extends xsbti.Exit
}
