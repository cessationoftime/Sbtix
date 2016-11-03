package se.nullable.sbtix

import sbt._

case class ModuleInfo(modules:Set[ModuleID], report:ModuleReport)

object DependencyReport {

  def fromConfigurationReport(report: ConfigurationReport): Seq[ModuleInfo] = {
    // def orgModules(orgReport: OrganizationArtifactReport): Seq[ModuleInfo] = {
    //   val chosenVersion = orgReport.modules.find(!_.evicted).map(_.module.revision)
    //   orgReport.modules.map(chosenModules(chosenVersion))
    // }

    // def chosenModules(chosenVersion: Option[String])(report: ModuleReport): ModuleInfo = {
    //   val chosenVersionFilter = chosenVersion.filter(_ => report.evicted)
    //   ModuleInfo(
    //     report,
    //     chosenVersionFilter
    //     )
    // }
    report.details.flatMap(_.modules).map{modReport=>

//if (modReport.evicted) {
println("ModReport!: " + modReport)
//}

     ModuleInfo(
      (modReport.callers.map(_.caller).toSet + modReport.module).toSet,
         modReport
         )
  }
}
}
