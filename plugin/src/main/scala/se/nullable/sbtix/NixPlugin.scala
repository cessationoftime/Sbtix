package se.nullable.sbtix

//import net.virtualvoid.sbt.graph._
import coursier.CoursierPlugin
import coursier.Dependency
import coursier.FromSbt
import sbt.Keys._
import sbt._
import CrossVersion._

object NixPlugin extends AutoPlugin {

  import autoImport._

  object ModuleBasicId {

    def fromModuleID(m:ModuleID) = {
       ModuleBasicId(m.organization,m.name,m.revision)
    }

    def filterBasicId(exclude:Set[ModuleBasicId])(m:ModuleID) : Option[ModuleID] = {
      if (exclude.contains(fromModuleID(m))) {
          None
        } else {
          Some(m)
          }  
       
    

  }
}

case class ModuleBasicId(organization: String, name: String, revision: String)

  def sbtReportTask = Def.task {
    val upd = ignoreMissingUpdate.value
    val conf = configuration.value
    upd.configuration(conf.name).map(DependencyReport.fromConfigurationReport).getOrElse(Seq.empty)
  }


  /**
   * This is copied directly from jrudolph/sbt-dependency-graph/DependencyGraphSettings.scala.
   * It might be possible to discard this def.  But I'd like to see how things work with it in place first.
   */
  def ignoreMissingUpdateT =
    ignoreMissingUpdate <<= Def.task {
      val depsUpdated = transitiveUpdate.value.exists(!_.stats.cached)
      val isRoot = executionRoots.value contains resolvedScoped.value
      val s = streams.value
      val scalaProvider = appConfiguration.value.provider.scalaProvider

      // Only substitute unmanaged jars for managed jars when the major.minor parts of the versions the same for:
      //   the resolved Scala version and the scalaHome version: compatible (weakly- no qualifier checked)
      //   the resolved Scala version and the declared scalaVersion: assume the user intended scalaHome to override anything with scalaVersion
      def subUnmanaged(subVersion: String, jars: Seq[File]) = (sv: String) ⇒
        (partialVersion(sv), partialVersion(subVersion), partialVersion(scalaVersion.value)) match {
          case (Some(res), Some(sh), _) if res == sh     ⇒ jars
          case (Some(res), _, Some(decl)) if res == decl ⇒ jars
          case _                                         ⇒ Nil
        }
      val subScalaJars: String ⇒ Seq[File] = SbtAccess.unmanagedScalaInstanceOnly.value match {
        case Some(si) ⇒ subUnmanaged(si.version, si.jars)
        case None     ⇒ sv ⇒ if (scalaProvider.version == sv) scalaProvider.jars else Nil
      }
      val transform: UpdateReport ⇒ UpdateReport = r ⇒ Classpaths.substituteScalaFiles(scalaOrganization.value, r)(subScalaJars)

      val show = Reference.display(thisProjectRef.value)
      Classpaths.cachedUpdate(s.cacheDirectory, show, ivyModule.value, (updateConfiguration in ignoreMissingUpdate).value, transform, skip = (skip in update).value, force = isRoot, depsUpdated = depsUpdated, log = s.log)
    }


  lazy val genNixProjectTask : sbt.Def.Initialize[sbt.Task[(Seq[se.nullable.sbtix.GenericModule], Seq[sbt.Resolver])]] =
    Def.task {
        val sVersion = scalaVersion.value
        val sbVersion = scalaBinaryVersion.value
    	val log = sLog.value
      val fetcher = new CoursierArtifactFetcher(
        sVersion,
        sbVersion,
        log
      )
 
 val allCrossProjectIds =  projectID.all(filter).value.toSet.map(CrossVersion(scalaVersion.value, scalaBinaryVersion.value))
 println("allprojectIDS: " + allCrossProjectIds)
 val excludeBasicIds = allCrossProjectIds.map(ModuleBasicId.fromModuleID)
      val modules : Set[sbt.ModuleID]  = sbtReportTask.all(filter).value.flatten.flatMap(_.modules).toSet.flatMap(ModuleBasicId.filterBasicId(excludeBasicIds))

     //val deps : Set[coursier.Dependency] = modules.map(graphToCoursierDep).toSet;
     // val dep = coursier.Dependency(coursier.Module("com.github.alexarchambault", "argonaut-shapeless_6.1_2.11"), "0.2.0")

    val rawDeps = allDependencies.value.toSet -- projectDependencies.value;

    val originalDeps = rawDeps.flatMap(FromSbt.dependencies(_, sVersion, sbVersion, "jar")).map(_._2);

val reportDeps = (rawDeps ++ modules).flatMap(FromSbt.dependencies(_, sVersion, sbVersion, "jar")).map(_._2);

     // log.info("\n\n!!!graphDeps: " + modules.toString)

     // log.info("\n\n!!!coursierDeps: " + originalDeps.toString)
    
      //val isDotty = ScalaInstance.isDotty(sVersion)
      // WE ARE GETTING THE CORRECT INFORMATION. COURSIER IGNORES DUPLICATE MODULES AND TAKES THE MORE RECENT VERSION!! Send it one module at a time.
   //   val (a,b) = reportDeps.toSeq.map(fetcher.buildNixProject(externalResolvers.all(filter).value.flatten, CoursierPlugin.autoImport.coursierCredentials.value)).unzip
     // (a.flatten,b.flatten)
     (Seq.empty,Seq.empty)
    }
  lazy val genNixCommand =
    Command.command("genNix") { initState =>
      val extracted = Project.extract(initState)
      val repoFile = extracted.get(nixRepoFile)
      var state = initState

      val moduleResolversTupleCollection = (for {
        project <- extracted.structure.allProjectRefs
        modulesResolversTuple <- Project.runTask(genNixProject in project, state) match {
          case Some((_state, Value(taskOutput))) =>
            state = _state
            Some(taskOutput)
          case Some((_state, Inc(inc:Incomplete))) => 
            state = _state
            state.log.error(s"genNixProject task did not complete $inc for project $project")
            None
          case None =>
            state.log.warn(s"NixPlugin not enabled for project $project, skipping...")
            None
        }
      } yield modulesResolversTuple)

      val (modulesSeqSeq,resolversSeqSeq) = moduleResolversTupleCollection.unzip

      val modules = modulesSeqSeq.flatten.distinct
      val resolvers = resolversSeqSeq.flatten.distinct

      IO.write(repoFile, NixWriter(state.log, modules, resolvers))
      state
    }

  override def requires: Plugins = CoursierPlugin

  override def trigger: PluginTrigger = allRequirements

  val filter = ScopeFilter( inAnyProject, inConfigurations(Compile, Test, IntegrationTest, Runtime, Provided, Optional) )

  override def projectSettings = Seq(
   updateConfiguration in ignoreMissingUpdate <<= updateConfiguration(config ⇒ new UpdateConfiguration(config.retrieve, true, config.logging)),

    nixRepoFile := baseDirectory.value / "repo.nix",
    genNixProject:= genNixProjectTask.value,
    commands += genNixCommand

  )

  object autoImport {
    val nixRepoFile = settingKey[File]("the path to put the nix repo definition in")
    val genNixProject = taskKey[(Seq[se.nullable.sbtix.GenericModule], Seq[sbt.Resolver])]("generate a Nix definition for building the maven repo")
      val ivyReport = TaskKey[File]("ivy-report",
    "A task which returns the location of the ivy report file for a given configuration (default `compile`).")
    val ignoreMissingUpdate = Keys.update in ivyReport
  }

}
