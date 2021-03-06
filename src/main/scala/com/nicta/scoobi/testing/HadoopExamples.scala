package com.nicta.scoobi.testing

import org.specs2.execute._
import org.specs2.time.SimpleTimer
import com.nicta.scoobi.ScoobiConfiguration
import org.specs2.specification._
import org.specs2.Specification
import org.specs2.main.CommandLineArguments
import java.util.logging.Level
import com.nicta.scoobi.impl.control.Exceptions._


/**
 * This trait provides an Around context to be used in a Specification
 *
 * Subclasses need to define the context method:
 *
 *  - def context = local            // execute the code locally
 *  - def context = cluster          // execute the code on the cluster
 *  - def context = localThenCluster // execute the code locally then on the cluster in case of a success
 *
 * They also need to implement the Cluster trait to specify the location of the remote nodes
 *
 */
trait HadoopExamples extends WithHadoop with AroundContextExample[Around] with CommandLineArguments {

  /** make the context available implicitly as an Outside[ScoobiConfiguration] so that examples taking that context as a parameter can be declared */
  implicit protected def aroundContext: HadoopContext = context

  /** define the context to use: local, cluster or localThenCluster */
  def context: HadoopContext = localThenCluster

  /**
   * the execution time will not be displayed with this function, but by adding more information to the execution Result
   */
  override def displayTime(prefix: String) = (timer: SimpleTimer) => ()

  /** context for local execution */
  def local: HadoopContext = new LocalHadoopContext
  /** context for cluster execution */
  def cluster: HadoopContext = new ClusterHadoopContext
  /** context for local then cluster execution */
  def localThenCluster: HadoopContext = new LocalThenClusterHadoopContext

  /** execute an example body on the cluster */
  def remotely[R <% Result](r: =>R) = showResultTime("Cluster execution time", runOnCluster(r))

  /** execute an example body locally */
  def locally[R <% Result](r: =>R) = showResultTime("Local execution time", runOnLocal(r))

  /**
   * Context for running examples on the cluster
   */
  class ClusterHadoopContext extends HadoopContext {
    def outside = configureForCluster(new ScoobiConfiguration)

    def around[R <% Result](a: =>R) = remotely(a)
    override def apply[R <% Result](a: ScoobiConfiguration => R) = {
      if (arguments.keep("hadoop") || arguments.keep("cluster")) super.apply(a(outside))
      else                                                       Skipped("excluded", "No cluster execution time")
    }
  }

  /**
   * Context for running examples locally
   */
  class LocalHadoopContext extends HadoopContext {
    def outside = configureForLocal(new ScoobiConfiguration)

    def around[R <% Result](a: =>R) = locally(a)

    override def apply[R <% Result](a: ScoobiConfiguration => R) = {
      if (arguments.keep("hadoop") || arguments.keep("local")) super.apply(a(outside))
      else                                                     Skipped("excluded", "No local execution time")
    }

    override def isRemote = false
  }

  /**
   * Context for running examples locally, then on the cluster if it succeeds locally
   */
  case class LocalThenClusterHadoopContext() extends ClusterHadoopContext {
    /** simply return a */
    override def around[R <% Result](a: =>R) = a
    /**
     * delegate the apply method to the LocalContext, then the Cluster context in case of a Success
     */
    override def apply[R <% Result](a: ScoobiConfiguration => R) = {
      local(a) match {
        case f @ Failure(_,_,_,_) => f
        case e @ Error(_,_)       => e
        case s @ Skipped(_,_)     => cluster(a).mapExpected((e: String) => s.expected+"\n"+e)
        case other                => changeSeparator(other and cluster(a))
      }
    }
  }

  /** change the separator of a Result */
  private def changeSeparator(r: Result) = r.mapExpected((_:String).replace("; ", "\n"))
  /**
   * trait for creating contexts having ScoobiConfigurations
   *
   * the isLocalOnly method provides a hint to speed-up the execution (because there's no need to upload jars if a run
   * is local)
   */
  trait HadoopContext extends AroundOutside[ScoobiConfiguration] {
    def isRemote = true
  }

  override def showTimes = arguments.commandLine.arguments.exists(_.matches("scoobi.*.times.*"))   || super.showTimes
  override def quiet     = !verboseArg.isDefined && super.quiet
  override def level     = extractLevel(verboseArg.getOrElse(""))

  private[testing]
  def verboseArg = arguments.commandLine.arguments.find { _.matches("scoobi.*verbose.*") }

  private[testing]
  def verboseDetails(arg: String) = arg.split("\\.").toSeq.filterNot(_=="scoobi").filterNot(_=="verbose")

  private[testing]
  def extractLevel(arg: String) =
    verboseDetails(arg).flatMap(l => tryo(Level.parse(l.toUpperCase))).headOption.getOrElse(Level.INFO)

  /**
   * @return an executed Result updated with its execution time
   */
  private def showResultTime[T <% Result](prefix: String, t: =>T): Result = {
    if (showTimes) {
      lazy val (result, timer) = withTimer(ResultExecution.execute(t)(implicitly[T => Result]))
      result.updateExpected(prefix+": "+timer.time)
    } else t
  }


}

/**
 * You can use this abstract class to create your own specification class, specifying:
 *
 *  - the type of Specification: mutable or not
 *  - the cluster
 *  - additional variables
 *
 *      class MyHadoopSpec(args: Arguments) extends HadoopSpecificationStructure(args) with
 *        MyCluster with
 *        mutable.Specification
 */
trait HadoopSpecificationStructure extends
  HadoopHomeDefinedCluster with
  HadoopExamples with
  UploadedLibJars with
  SpecificationStructure {

  override def map(fs: =>Fragments) = super.map(fs).insert(Step(setLogFactory()))
}

/**
 * Hadoop specification with an acceptance specification
 */
trait HadoopSpecification extends Specification with HadoopSpecificationStructure



