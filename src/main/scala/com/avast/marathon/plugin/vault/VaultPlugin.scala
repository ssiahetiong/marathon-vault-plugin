package com.avast.marathon.plugin.vault

import java.io.File

import com.bettercloud.vault.{SslConfig, Vault, VaultConfig}
import mesosphere.marathon.plugin.plugin.PluginConfiguration
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.plugin.{ApplicationSpec, PodSpec, RunSpec, Secret}
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos.ExecutorInfo.Builder
import org.apache.mesos.Protos.{TaskGroupInfo, TaskInfo}
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, _}
import scala.collection.JavaConversions._

import scala.util.{Failure, Success, Try}

case class Configuration(address: String, token: String, sharedPathRoot: Option[String], privatePathRoot: Option[String], ssl: Option[SslConfiguration])
case class SslConfiguration(verify: Option[Boolean], trustStoreFile: Option[String], keyStoreFile: Option[String], keyStorePassword: Option[String],
                            pemFile: Option[String], clientPemFile: Option[String], clientKeyPemFile: Option[String])

class VaultPlugin extends RunSpecTaskProcessor with PluginConfiguration {

  private val logger = LoggerFactory.getLogger(classOf[VaultPlugin])
  logger.info("Vault plugin instantiated")

  private var vault: Vault = _
  private var sharedPathProvider: VaultPathProvider = _
  private var privatePathProvider: VaultPathProvider = _

  override def initialize(marathonInfo: Map[String, Any], configurationJson: JsObject): Unit = {
    implicit val sslConfigurationFormat: OFormat[SslConfiguration] = Json.format[SslConfiguration]
    val conf = configurationJson.as[Configuration](Json.format[Configuration])
    assert(conf != null, "VaultPlugin not initialized with configuration info.")
    assert(conf.address != null, "Vault address not specified.")
    assert(conf.token != null, "Vault token not specified.")
    val sslConfig = new SslConfig()
    conf.ssl.foreach(sc => {
      sc.verify.foreach(sslConfig.verify(_))
      sc.trustStoreFile.foreach(f => sslConfig.trustStoreFile(new File(f)))
      sc.keyStoreFile.foreach(f => sslConfig.keyStoreFile(new File(f), sc.keyStorePassword.getOrElse("")))
      sc.pemFile.foreach(f => sslConfig.pemFile(new File(f)))
      sc.clientPemFile.foreach(f => sslConfig.clientPemFile(new File(f)))
      sc.clientKeyPemFile.foreach(f => sslConfig.clientKeyPemFile(new File(f)))
    })
    vault = new Vault(new VaultConfig().address(conf.address).token(conf.token).sslConfig(sslConfig.build()).build())
    sharedPathProvider = new SharedPathProvider(conf.sharedPathRoot.getOrElse("/"))
    privatePathProvider = new PrivatePathProvider(conf.privatePathRoot.getOrElse("/"))
    logger.info(s"VaultPlugin initialized with $conf")
  }

  def taskInfo(appSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit = {
    apply(appSpec, builder)
  }

  def taskGroup(podSpec: PodSpec, executor: Builder, taskGroup: TaskGroupInfo.Builder): Unit = {
    taskGroup.getTasksList.foreach { task =>
      apply(podSpec, task.toBuilder)
    }
  }

  private def apply(runSpec: RunSpec, builder: TaskInfo.Builder): Unit = {
    val envBuilder = builder.getCommand.getEnvironment.toBuilder
    runSpec.secrets.foreach {
      case(name, secret) =>
        val pathProvider = selectPathProvider(secret.source).getPath(runSpec, builder)
        getSecretValueFromVault(secret)(pathProvider) match {
          case Success(secretValue) => envBuilder.addVariables(Variable.newBuilder().setName(name).setValue(secretValue))
          case Failure(e) => logger.error(s"Secret ${name} in ${runSpec.id} application cannot be read from Vault (source: ${secret.source})", e)
        }
    }

    val commandBuilder = builder.getCommand.toBuilder
    commandBuilder.setEnvironment(envBuilder)
    builder.setCommand(commandBuilder)
  }

  private def selectPathProvider(secret: String): VaultPathProvider = {
    if (secret.startsWith("/")) sharedPathProvider
    else privatePathProvider
  }

  private def getSecretValueFromVault(secret: Secret)(getVaultPath: String => String): Try[String] = Try {
    val source = secret.source
    val indexOfAt = source.indexOf('@')
    val indexOfSplit = if (indexOfAt != -1) indexOfAt else source.lastIndexOf('/')
    if (indexOfSplit > 0) {
      val path = source.substring(0, indexOfSplit)
      val attribute = source.substring(indexOfSplit + 1)
      Option(vault.logical().read(getVaultPath(path)).getData.get(attribute)) match {
        case Some(secretValue) => Success(secretValue)
        case None => Failure(new RuntimeException(s"Secret $source obtained from Vault is empty"))
      }
    } else {
      Failure(new RuntimeException(s"Secret $source cannot be read because it cannot be parsed"))
    }
  }.flatten
}

trait VaultPathProvider {
  def getPath(runSpec: RunSpec, builder: TaskInfo.Builder): String => String
}

class SharedPathProvider(root: String) extends VaultPathProvider {
  override def getPath(runSpec: RunSpec, builder: TaskInfo.Builder): String => String =
    path => s"$root${if (root.endsWith("/")) "" else "/"}${path.substring(1)}"
}

class PrivatePathProvider(root: String) extends VaultPathProvider {
  override def getPath(runSpec: RunSpec, builder: TaskInfo.Builder): String => String =
    path => s"$root${if (root.endsWith("/")) "" else "/"}${runSpec.id.path.mkString("/")}/$path"
}
