package se.nullable.sbtix

import net.virtualvoid.sbt.graph._
import coursier.CoursierPlugin
import sbt.Keys._
import sbt._
object NixPlugin extends AutoPlugin {

  import autoImport._

def graphToCoursierDep(m:Module) : coursier.Dependency = {

println(m.extraInfo)

    //val module0 = Module(m.id.organization, m.id.name, FromSbt.attributes(module.extraDependencyAttributes))
    val module0 = Module(m.id.organization, m.id.name)
    val version = m.id.version
  
 val dep = Dependency(
      module0,
      version,
      exclusions = module.exclusions.map { rule =>
        // FIXME Other `rule` fields are ignored here
        (rule.organization, rule.name)
      }.toSet,
      transitive = module.isTransitive
    )


	coursier.Dependency(coursier.Module(m.id.organisation, m.id.name), m.id.version)
}

  lazy val genNixProjectTask =
    Def.task {
    	val log = sLog.value
      val fetcher = new CoursierArtifactFetcher(
        scalaVersion.value,
        scalaBinaryVersion.value,
        log
      )
      import DependencyGraphKeys._
 
      val modules = moduleGraph.all(filter).value.flatMap(_.modules.values.toSeq.distinct.sortBy(m ⇒ (m.id.organisation, m.id.name)))

val deps : Set[coursier.Dependency] = modules.map(graphToCoursierDep).toSet
     // val dep = coursier.Dependency(coursier.Module("com.github.alexarchambault", "argonaut-shapeless_6.1_2.11"), "0.2.0")

// modules.flatMap(FromSbt.dependencies(_, scalaVersion, scalaBinaryVersion, "jar")).map(_._2)

    //  log.info(modules.toString)
      val sVersion = scalaVersion.value
      val isDotty = ScalaInstance.isDotty(sVersion)
        fetcher.buildNixProject(thisProjectRef.value,deps, externalResolvers.all(filter).value.flatten, CoursierPlugin.autoImport.coursierCredentials.value)
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

  override def requires: Plugins = CoursierPlugin && DependencyGraphPlugin

  override def trigger: PluginTrigger = allRequirements

  val filter = ScopeFilter( inAnyProject, inConfigurations(Compile, Test, IntegrationTest, Runtime, Provided, Optional) )

  override def projectSettings = Seq(
    nixRepoFile := baseDirectory.value / "repo.nix",
   
    genNixProject:= genNixProjectTask.value,
    commands += genNixCommand
  //  DependencyGraphKeys.filterScalaLibrary in Global := false //Not sure if this helps anything or if this is the correct method to change the filterScalaLibrary
  )

  object autoImport {
    val nixRepoFile = settingKey[File]("the path to put the nix repo definition in")
    val genNixProject = taskKey[(Seq[se.nullable.sbtix.GenericModule], Seq[sbt.Resolver])]("generate a Nix definition for building the maven repo")
  }

}
