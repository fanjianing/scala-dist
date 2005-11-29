package scbaz;

import scbaz.messages._ ;
import java.io.{File,StringReader} ;
import java.nio._ ;
import java.net._ ;
import java.nio.channels._ ;
import scala.xml.XML ;
import scala.collection.mutable.{HashSet, Queue} ;


// A command line from the user.  This is the front end of the
// command-line interface to the Scala Bazaar system.
object CommandLine {
  // global options

  // the directory that is being managed
  // XXX the default directory should come from
  //     the SCALA_HOME environment variable if present...
  //     that way, typing "scbaz" will work fine by default.
  var dirname = new File(".");
  var dir:ManagedDirectory = null ;

  // whether to actually do the requested work, or to
  // just print out what would be done
  var dryrun = false;
  

  def error_exit(message: String):All = {
    Console.println("error: " + message);
    java.lang.System.exit(2).asInstanceOf[All];
  }

  def print_usage() = {
    Console.println("scbaz [ -d directory ] [ -n ] command command_options...");
    Console.println("setup - initialize a directory to be managed");
    Console.println("setuniverse - set the universe for a directory");
    Console.println("update - update the list of available packages");
    Console.println("install - install a package");
    Console.println("remove - remove a package");
    Console.println("upgrade - upgrade all packages that can be");
    Console.println("installed - list the packages that are installed");
    Console.println("available - list the available packages for installation");
    Console.println("compact - clear the download cache to save space");

    Console.println("share - upload a package description to the universe");
    Console.println("retract - retract a previously uploaded package");
  }

  def usage_exit():All = {
    print_usage();
    java.lang.System.exit(2) .asInstanceOf[All];
  }

  def setup(args:List[String]) = {
    if(args.length > 0)
      usage_exit();

    val scbaz_dirname = new File(dirname, "scbaz");

    if(scbaz_dirname.exists())
      error_exit("the directory " + dirname + " looks like it is already set up");

    
    scbaz_dirname.mkdirs();
    // XXX it would be nice to make the scbaz directory non-readable
    //     by anyone but the user....
  }


  def setuniverse(args:List[String]) = {
    if(args.length != 1)
      error_exit("setuniverse requires 1 argument: the universe description.");

    val unode = XML.load(new StringReader(args(0)));
    val univ = Universe.fromXML(unode);

    if(!dryrun) {
      dir.setUniverse(univ);
     
      Console.println("Universe established.  You should probably run \"scbaz update\".");
    }
  }

  def install(args:List[String]) = {
    for(val arg <- args) {
      val userSpec = UserPackageSpecifierUtil.fromString(arg);
      val spec = userSpec.chooseFrom(dir.available) match {
	case None =>
	  throw new Error("No available package matches " + arg + "!");

	case Some(pack) =>
	  pack.spec
      };

      val packages = dir.available.choosePackagesFor(spec) ;

      for(val pack <- packages) {
	if(! dir.installed.includes(pack.spec)) {
	  Console.println("installing " + pack.spec);

	  // XXX this should give a nice error message on dependency errors
	  if(! dryrun)
	    dir.install(pack);
	}
      }
    }
  }

  def remove(args:List[String]) = {
    for(val name <- args) {
      dir.installed.entryNamed(name) match {
	case None => {
	  Console.println("no package named " + name);
	} ;
	case Some(entry) => {
	  if(dir.installed.anyDependOn(entry.name)) {
	    val needers = dir.installed.entriesDependingOn(entry.name) ;
	    val neednames = needers.map(.name) ;

	    // XXX the below has an ugly List() in it
	    error_exit("package " + entry + " is needed by " + neednames) ;
	  }

	  Console.println("removing " + entry.packageSpec);
	  if(! dryrun)
	    dir.remove(entry);
	}
      }
    }
  }

  def upgrade(args: List[String]) = {
    if(! args.isEmpty)
      usage_exit();

    // store both a set of specs in addition to the sequence of
    // packages to install, so as to improve performance
    val packsToInstall = new Queue[Package];
    val specsToInstall = new HashSet[PackageSpec];

    for(val cur <- dir.installed.sortedPackageSpecs) {
      // the iteration is in sorted order so that
      // the behavior is deterministic

      dir.available.newestNamed(cur.name) match {
	case None =>
	  /* package stream is no longer in the universe; ignore it */
	  ();
	case Some(newest) => {
	  if(! newest.spec.equals(cur)) {
	    try {
	      // try to upgrade from cur to newest
	      val allNeeded = dir.available.choosePackagesFor(newest.spec);
	      val newNeeded =
		allNeeded.toList
		  .filter(p => ! dir.installed.includes(p.spec))
		  .filter(p => ! specsToInstall.contains(p.spec));
	      
	      packsToInstall ++= newNeeded;
	      specsToInstall ++= newNeeded.map(.spec);
	    } catch {
	      // its dependencies have a problem;
	      // continue on trying to upgrade others
	      case _:DependencyError =>  {
		Console.println("Cannot upgrade to " + newest + " because of a failed dependency.");
	      }
	    }
	  }
	}
      }
    }

    if(packsToInstall.isEmpty)
      Console.println("Nothing to upgrade.")
    else {
      for(val spec <- specsToInstall) {
	val pack = dir.available.packageWithSpec(spec) match {
	  case Some(p) => p;
	  case None => throw new Error("internal error: package " + spec + " not actually available?!");
	}
	Console.println("Installing " + pack.spec + "...");
	if(! dryrun)
	  dir.install(pack);
      }
    }
  }

  def installed(args:List[String]) = {
    if(! args.isEmpty)
      usage_exit();

    val sortedSpecs = dir.installed.sortedPackageSpecs ;

    for(val spec <- sortedSpecs) {
      Console.println(spec);
    }
    Console.println(sortedSpecs.length.toString() + " packages installed")
  }

  def available(args:List[String]) = {
    if(! args.isEmpty)
      usage_exit();

    val sortedSpecs = dir.available.sortedSpecs ;

    for(val spec <- sortedSpecs) {
      Console.println(spec);
    }
    Console.println(sortedSpecs.length.toString() + " packages available")
  }

  def update(args:List[String]) = {
    if(! args.isEmpty)
      usage_exit();

    if(! dryrun) {
      // XXX this should catch errors and report them gracefully
      dir.updateAvailable();
    }
  }


  // XXX bogusly choose a simple universe to connect to
  private def chooseSimple = {
    dir.universe.simpleUniverses.reverse(0)
  }

  // add a package
  def share(args:List[String]):Unit = {
    val pack = args match {
      case List("--template") => {
	Console.println("<package>");
	Console.println("  <name></name>");
	Console.println("  <version></version>");
	Console.println("  <link></link>");
	Console.println("  <depends></depends>");
	Console.println("  <description></description>");
	Console.println("</package>");
	null;
      }


      case List("-f", fname) =>
	PackageUtil.fromXML(XML.load(fname));
      
      case List(arg) =>
	PackageUtil.fromXML(XML.load(new StringReader(arg)));
      // XXX if the above fails, check if there is a file;
      // if so, tell the user maybe that is what they meant
      
      case _ => usage_exit();  // XXX need usage for add
    }

    if(pack == null)
      return();

    // XXX this should do some sanity checks on the package:
    //  non-empty name, version, etc.
    //  name is only characters, numbers, dashes, etc.
    //  spec is not already included; retract first if you want
    //    to replace something

    if(! dryrun) {
      chooseSimple.requestFromServer(AddPackage(pack));
      // XXX should check the reply
    }
  }


  // remove a package from the bazaar
  def retract(args:List[String]):Unit = {
    args match {
      case List(rawspec) => {
	val spec =
	  try {
	    PackageSpecUtil.fromSlashNotation(rawspec);
	  } catch{
	    case ex:FormatError => {
	      error_exit("Badly formed package specification: " + rawspec);
	    }
	    case ex@_ => throw ex;
	  };
	    
	Console.println("removing " + spec + "...");
	if(! dryrun) {
	  chooseSimple.requestFromServer(RemovePackage(spec));
	  // XXX should check the reply
	}
      }
      case _ => {
	Console.println("Specify a package name and version to retract from the server.");
	Console.println("For example: scbaz retract foo/1.3");
      }
    }
  }

  def processCommandLine(args:Array[String]):Unit = {
    var argsleft = args.toList ;

    while(true) {
      argsleft match {
	case Nil =>
	  usage_exit();
	case arg :: rest => {
	  argsleft = rest ;

	  arg match {
	    case "-n" => {
	      dryrun = true;
	    }
	    case "-d" => {
	      argsleft match {
		case Nil => usage_exit();
		case arg :: rest => {
		  argsleft = rest;
		  dirname = new File(arg);
		}
	      }
	    }

	    case _ => {
	      // not a global option; the command has been reached

	      dir = new ManagedDirectory(dirname);

	      arg match {
		case "setup" => return setup(rest);
		case "setuniverse" => return setuniverse(rest);
		case "install" => return install(rest);
		case "remove" => return remove(rest);
		case "installed" => return installed(rest);
		case "available" => return available(rest);
		case "update" => return update(rest);
		case "upgrade" => return upgrade(rest);

		case "share" => return share(rest);
		case "retract" => return retract(rest);

		case _ => usage_exit();
	      }
	    }
	  }
	}
      }
    }
  }

  def main(args:Array[String]) = {
    this.processCommandLine(args);
  }
}
