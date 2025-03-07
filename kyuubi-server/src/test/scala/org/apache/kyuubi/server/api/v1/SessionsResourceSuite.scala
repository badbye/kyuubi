/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.api.v1

import java.nio.charset.StandardCharsets
import java.util
import java.util.Base64
import javax.ws.rs.client.Entity
import javax.ws.rs.core.{GenericType, MediaType, Response}

import scala.collection.JavaConverters._

import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

import org.apache.kyuubi.{KyuubiFunSuite, RestFrontendTestHelper}
import org.apache.kyuubi.client.api.v1.dto
import org.apache.kyuubi.client.api.v1.dto._
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiReservedKeys.KYUUBI_SESSION_CONNECTION_URL_KEY
import org.apache.kyuubi.engine.ShareLevel
import org.apache.kyuubi.metrics.{MetricsConstants, MetricsSystem}
import org.apache.kyuubi.operation.OperationHandle
import org.apache.kyuubi.server.http.authentication.AuthenticationHandler.AUTHORIZATION_HEADER
import org.apache.kyuubi.session.SessionType

class SessionsResourceSuite extends KyuubiFunSuite with RestFrontendTestHelper {

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventually(timeout(10.seconds), interval(200.milliseconds)) {
      assert(MetricsSystem.counterValue(MetricsConstants.REST_CONN_OPEN).getOrElse(0L) === 0)
    }
  }

  test("open/close and count session") {
    val requestObj = new SessionOpenRequest(Map("testConfig" -> "testValue").asJava)

    var response = webTarget.path("api/v1/sessions")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(requestObj, MediaType.APPLICATION_JSON_TYPE))

    assert(200 == response.getStatus)

    val sessionHandle = response.readEntity(classOf[SessionHandle]).getIdentifier

    assert(sessionHandle !== null)

    val statistic = webTarget.path("api/v1/sessions/execPool/statistic").request().get()
    val execPoolStatistic1 = statistic.readEntity(classOf[ExecPoolStatistic])
    assert(execPoolStatistic1.getExecPoolSize == 1 &&
      execPoolStatistic1.getExecPoolActiveCount == 1)

    response = webTarget.path("api/v1/sessions/count").request().get()
    val openedSessionCount = response.readEntity(classOf[SessionOpenCount])
    assert(openedSessionCount.getOpenSessionCount == 1)

    // close an opened session
    response = webTarget.path(s"api/v1/sessions/$sessionHandle").request().delete()
    assert(200 == response.getStatus)

    response = webTarget.path("api/v1/sessions/count").request().get()
    val openedSessionCount2 = response.readEntity(classOf[SessionOpenCount])
    assert(openedSessionCount2.getOpenSessionCount == 0)
  }

  test("getSessionList") {
    val requestObj = new SessionOpenRequest(Map("testConfig" -> "testValue").asJava)

    var response = webTarget.path("api/v1/sessions")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(requestObj, MediaType.APPLICATION_JSON_TYPE))

    // get session list
    var response2 = webTarget.path("api/v1/sessions").request().get()
    assert(200 == response2.getStatus)
    val sessions1 = response2.readEntity(new GenericType[Seq[SessionData]]() {})
    assert(sessions1.nonEmpty)
    assert(sessions1.head.getConf.get(KYUUBI_SESSION_CONNECTION_URL_KEY) === fe.connectionUrl)

    // close an opened session
    val sessionHandle = response.readEntity(classOf[SessionHandle]).getIdentifier
    response = webTarget.path(s"api/v1/sessions/$sessionHandle").request().delete()
    assert(200 == response.getStatus)

    // get session list again
    response2 = webTarget.path("api/v1/sessions").request().get()
    assert(200 == response2.getStatus)
    val sessions2 = response2.readEntity(classOf[Seq[SessionData]])
    assert(sessions2.isEmpty)
  }

  test("get session event") {
    val sessionOpenRequest = new SessionOpenRequest(Map("testConfig" -> "testValue").asJava)

    val user = "kyuubi".getBytes()

    val sessionOpenResp = webTarget.path("api/v1/sessions")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .header(
        AUTHORIZATION_HEADER,
        s"Basic ${new String(Base64.getEncoder().encode(user), StandardCharsets.UTF_8)}")
      .post(Entity.entity(sessionOpenRequest, MediaType.APPLICATION_JSON_TYPE))

    val sessionHandle = sessionOpenResp.readEntity(classOf[SessionHandle]).getIdentifier

    // get session event
    var response = webTarget.path(s"api/v1/sessions/$sessionHandle").request().get()
    assert(200 == sessionOpenResp.getStatus)
    val sessions = response.readEntity(classOf[dto.KyuubiSessionEvent])
    assert(sessions.getConf.get("testConfig").equals("testValue"))
    assert(sessions.getSessionType.equals(SessionType.INTERACTIVE.toString))
    assert(sessions.getUser.equals("kyuubi"))

    // close an opened session
    response = webTarget.path(s"api/v1/sessions/$sessionHandle").request().delete()
    assert(200 == response.getStatus)

    // get session detail again
    response = webTarget.path(s"api/v1/sessions/$sessionHandle").request().get()
    assert(404 == response.getStatus)
  }

  test("get infoType") {
    val totalConnections =
      MetricsSystem.counterValue(MetricsConstants.REST_CONN_TOTAL).getOrElse(0L)
    val failedConnections =
      MetricsSystem.counterValue(MetricsConstants.REST_CONN_FAIL).getOrElse(0L)

    val requestObj = new SessionOpenRequest(Map(
      "testConfig" -> "testValue",
      KyuubiConf.SERVER_INFO_PROVIDER.key -> "SERVER").asJava)

    var response: Response = webTarget.path("api/v1/sessions")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(requestObj, MediaType.APPLICATION_JSON_TYPE))

    val sessionHandle = response.readEntity(classOf[SessionHandle]).getIdentifier

    response = webTarget.path(s"api/v1/sessions/$sessionHandle/info/13")
      .request().get()
    assert(200 == response.getStatus)
    val sessions = response.readEntity(classOf[InfoDetail])
    assert(sessions.getInfoType.equals("CLI_SERVER_NAME") &&
      sessions.getInfoValue.equals("Apache Kyuubi"))
    // Invalid sessionHandleStr
    val handle = "b88d6b56-d200-4bb6-bf0a-5da0ea572e11|0c4aad4e-ccf7-4abd-9305-943d4bfd2d9a|0"
    response = webTarget.path(s"api/v1/sessions/$handle/info/13").request().get()
    assert(404 == response.getStatus)
    response = webTarget.path(s"api/v1/sessions/0/info/13").request().get()
    assert(404 == response.getStatus)

    // Invalid infoType
    response = webTarget.path(s"api/v1/sessions/$sessionHandle/info/0")
      .request().get()
    assert(404 == response.getStatus)
    response = webTarget.path(s"api/v1/sessions/$sessionHandle/info/str")
      .request().get()
    assert(404 == response.getStatus)

    eventually(timeout(3.seconds), interval(200.milliseconds)) {
      assert(MetricsSystem.counterValue(
        MetricsConstants.REST_CONN_TOTAL).getOrElse(0L) - totalConnections === 6)
      assert(MetricsSystem.counterValue(MetricsConstants.REST_CONN_OPEN).getOrElse(0L) === 0)
      assert(MetricsSystem.counterValue(
        MetricsConstants.REST_CONN_FAIL).getOrElse(0L) - failedConnections === 4)
    }
  }

  test("submit operation and get operation handle") {
    val requestObj = new SessionOpenRequest(Map("testConfig" -> "testValue").asJava)

    var response: Response = webTarget.path("api/v1/sessions")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(requestObj, MediaType.APPLICATION_JSON_TYPE))

    val sessionHandle = response.readEntity(classOf[SessionHandle]).getIdentifier

    val pathPrefix = s"api/v1/sessions/$sessionHandle"

    val statementReq = new StatementRequest("show tables", true, 3000)
    response = webTarget
      .path(s"$pathPrefix/operations/statement").request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(statementReq, MediaType.APPLICATION_JSON_TYPE))
    assert(200 == response.getStatus)
    var operationHandle = response.readEntity(classOf[OperationHandle])
    assert(operationHandle !== null)

    response = webTarget.path(s"$pathPrefix/operations/typeInfo").request()
      .post(Entity.entity(null, MediaType.APPLICATION_JSON_TYPE))
    assert(200 == response.getStatus)
    operationHandle = response.readEntity(classOf[OperationHandle])
    assert(operationHandle !== null)

    response = webTarget.path(s"$pathPrefix/operations/catalogs")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(null, MediaType.APPLICATION_JSON_TYPE))
    assert(200 == response.getStatus)
    operationHandle = response.readEntity(classOf[OperationHandle])
    assert(operationHandle !== null)

    val getSchemasReq = new GetSchemasRequest("spark_catalog", "default")
    response = webTarget.path(s"$pathPrefix/operations/schemas")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(getSchemasReq, MediaType.APPLICATION_JSON_TYPE))
    assert(200 == response.getStatus)
    operationHandle = response.readEntity(classOf[OperationHandle])
    assert(operationHandle !== null)

    val tableTypes = new util.ArrayList[String]()
    val getTablesReq = new GetTablesRequest("spark_catalog", "default", "default", tableTypes)
    response = webTarget.path(s"$pathPrefix/operations/tables")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(getTablesReq, MediaType.APPLICATION_JSON_TYPE))
    assert(200 == response.getStatus)
    operationHandle = response.readEntity(classOf[OperationHandle])
    assert(operationHandle !== null)

    response = webTarget.path(s"$pathPrefix/operations/tableTypes").request()
      .post(Entity.entity(null, MediaType.APPLICATION_JSON_TYPE))
    assert(200 == response.getStatus)
    operationHandle = response.readEntity(classOf[OperationHandle])
    assert(operationHandle !== null)

    val getColumnsReq = new GetColumnsRequest("spark_catalog", "default", "default", "default")
    response = webTarget.path(s"$pathPrefix/operations/columns")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(getColumnsReq, MediaType.APPLICATION_JSON_TYPE))
    assert(200 == response.getStatus)
    operationHandle = response.readEntity(classOf[OperationHandle])
    assert(operationHandle !== null)

    val getFunctionsReq = new GetFunctionsRequest("default", "default", "default")
    response = webTarget.path(s"$pathPrefix/operations/functions")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(getFunctionsReq, MediaType.APPLICATION_JSON_TYPE))
    assert(200 == response.getStatus)
    operationHandle = response.readEntity(classOf[OperationHandle])
    assert(operationHandle !== null)

    val getPrimaryKeysReq = new GetPrimaryKeysRequest("spark_catalog", "default", "default")
    response = webTarget.path(s"$pathPrefix/operations/primaryKeys")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(getPrimaryKeysReq, MediaType.APPLICATION_JSON_TYPE))
    assert(404 == response.getStatus)

    val getCrossReferenceReq = new GetCrossReferenceRequest(
      "spark_catalog",
      "default",
      "default",
      "spark_catalog",
      "default",
      "default")
    response = webTarget.path(s"$pathPrefix/operations/crossReference")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(getCrossReferenceReq, MediaType.APPLICATION_JSON_TYPE))
    assert(404 == response.getStatus)
  }

  test("post session exception if failed to open engine session") {
    val requestObj = new SessionOpenRequest(Map(
      "spark.master" -> "invalid",
      KyuubiConf.ENGINE_SHARE_LEVEL.key -> ShareLevel.CONNECTION.toString).asJava)

    var response = webTarget.path("api/v1/sessions")
      .request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(requestObj, MediaType.APPLICATION_JSON_TYPE))

    val sessionHandle = response.readEntity(classOf[SessionHandle]).getIdentifier

    eventually(timeout(1.minutes), interval(200.milliseconds)) {
      response = webTarget.path(s"api/v1/sessions/$sessionHandle").request().get()
      // will meet json parse exception with response.readEntity(classOf[KyuubiSessionEvent])
      val sessionEvent = response.readEntity(classOf[String])
      assert(sessionEvent.contains("The last 10 line(s) of log are:"))
    }
  }
}
