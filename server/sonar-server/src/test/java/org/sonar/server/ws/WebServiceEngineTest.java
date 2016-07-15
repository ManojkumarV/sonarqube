/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.ws;

import java.io.IOException;
import java.util.Locale;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.i18n.I18n;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.RequestHandler;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.internal.ValidatingRequest;
import org.sonar.api.utils.log.LogTester;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.Errors;
import org.sonar.server.exceptions.Message;
import org.sonar.server.tester.UserSessionRule;
import org.sonarqube.ws.MediaTypes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WebServiceEngineTest {

  @Rule
  public LogTester logTester = new LogTester();

  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();

  I18n i18n = mock(I18n.class);

  WebServiceEngine underTest = new WebServiceEngine(new WebService[] {new SystemWs()}, i18n, userSessionRule);

  @Before
  public void start() {
    underTest.start();
  }

  @After
  public void stop() {
    underTest.stop();
  }

  @Test
  public void load_ws_definitions_at_startup() {
    assertThat(underTest.controllers()).hasSize(1);
    assertThat(underTest.controllers().get(0).path()).isEqualTo("api/system");
  }

  @Test
  public void execute_request() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/health");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("good");
  }

  @Test
  public void execute_request_when_path_does_not_begin_with_slash() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/health");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("good");
  }

  @Test
  public void execute_request_with_action_suffix() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/health");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("good");
  }

  @Test
  public void bad_request_if_action_suffix_is_not_supported() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/health.bat");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().status()).isEqualTo(400);
    assertThat(response.stream().mediaType()).isEqualTo(MediaTypes.JSON);
    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":[{\"msg\":\"Unknown action extension: bat\"}]}");
  }

  @Test
  public void no_content() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/alive");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEmpty();
  }

  @Test
  public void bad_controller() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/xxx/health");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":[{\"msg\":\"Unknown web service: api/xxx\"}]}");
  }

  @Test
  public void bad_action() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/xxx");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":[{\"msg\":\"Unknown action: api/system/xxx\"}]}");
  }

  @Test
  public void method_get_not_allowed() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/ping");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":[{\"msg\":\"HTTP method POST is required\"}]}");
  }

  @Test
  public void method_post_required() {
    ValidatingRequest request = new TestRequest().setMethod("POST").setPath("/api/system/ping");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("pong");
  }

  @Test
  public void unknown_parameter_is_set() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/fail_with_undeclared_parameter").setParam("unknown", "Unknown");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":[{\"msg\":\"BUG - parameter 'unknown' is undefined for action 'fail_with_undeclared_parameter'\"}]}");
  }

  @Test
  public void required_parameter_is_not_set() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/print");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":[{\"msg\":\"The 'message' parameter is missing\"}]}");
  }

  @Test
  public void optional_parameter_is_not_set() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/print").setParam("message", "Hello World");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("Hello World by -");
  }

  @Test
  public void optional_parameter_is_set() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/print")
      .setParam("message", "Hello World")
      .setParam("author", "Marcel");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("Hello World by Marcel");
  }

  @Test
  public void param_value_is_in_possible_values() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/print")
      .setParam("message", "Hello World")
      .setParam("format", "json");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("Hello World by -");
  }

  @Test
  public void param_value_is_not_in_possible_values() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/print")
      .setParam("message", "Hello World")
      .setParam("format", "html");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":[{\"msg\":\"Value of parameter 'format' (html) must be one of: [json, xml]\"}]}");
  }

  @Test
  public void internal_error() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/fail");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":[{\"msg\":\"Unexpected\"}]}");
    assertThat(response.stream().status()).isEqualTo(500);
    assertThat(response.stream().mediaType()).isEqualTo(MediaTypes.JSON);
  }

  @Test
  public void bad_request_with_i18n_message() {
    userSessionRule.setLocale(Locale.ENGLISH);
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/fail_with_i18n_message").setParam("count", "3");
    DumbResponse response = new DumbResponse();
    when(i18n.message(Locale.ENGLISH, "bad.request.reason", "bad.request.reason", 0)).thenReturn("reason #0");

    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo(
      "{\"errors\":[{\"msg\":\"reason #0\"}]}");
    assertThat(response.stream().status()).isEqualTo(400);
    assertThat(response.stream().mediaType()).isEqualTo(MediaTypes.JSON);
  }

  @Test
  public void bad_request_with_multiple_messages() {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/fail_with_multiple_messages").setParam("count", "3");
    DumbResponse response = new DumbResponse();

    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":["
      + "{\"msg\":\"Bad request reason #0\"},"
      + "{\"msg\":\"Bad request reason #1\"},"
      + "{\"msg\":\"Bad request reason #2\"}"
      + "]}");
    assertThat(response.stream().status()).isEqualTo(400);
    assertThat(response.stream().mediaType()).isEqualTo(MediaTypes.JSON);
  }

  @Test
  public void bad_request_with_multiple_i18n_messages() {
    userSessionRule.setLocale(Locale.ENGLISH);

    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/fail_with_multiple_i18n_messages").setParam("count", "3");
    DumbResponse response = new DumbResponse();
    when(i18n.message(Locale.ENGLISH, "bad.request.reason", "bad.request.reason", 0)).thenReturn("reason #0");
    when(i18n.message(Locale.ENGLISH, "bad.request.reason", "bad.request.reason", 1)).thenReturn("reason #1");
    when(i18n.message(Locale.ENGLISH, "bad.request.reason", "bad.request.reason", 2)).thenReturn("reason #2");

    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEqualTo("{\"errors\":[" +
      "{\"msg\":\"reason #0\"}," +
      "{\"msg\":\"reason #1\"}," +
      "{\"msg\":\"reason #2\"}]}");
    assertThat(response.stream().status()).isEqualTo(400);
    assertThat(response.stream().mediaType()).isEqualTo(MediaTypes.JSON);
  }

  @Test
  public void should_handle_headers() {
    DumbResponse response = new DumbResponse();
    String name = "Content-Disposition";
    String value = "attachment; filename=sonarqube.zip";
    response.setHeader(name, value);
    assertThat(response.getHeaderNames()).containsExactly(name);
    assertThat(response.getHeader(name)).isEqualTo(value);
  }

  @Test
  public void does_not_fail_when_request_is_aborted() throws Exception {
    ValidatingRequest request = new TestRequest().setMethod("GET").setPath("/api/system/fail_with_client_abort_exception");
    DumbResponse response = new DumbResponse();
    underTest.execute(request, response);

    assertThat(response.stream().outputAsString()).isEmpty();
    assertThat(logTester.logs(LoggerLevel.WARN)).isNotEmpty();
  }

  static class SystemWs implements WebService {
    @Override
    public void define(Context context) {
      NewController newController = context.createController("api/system");
      createNewDefaultAction(newController, "health")
        .setHandler(new RequestHandler() {
          @Override
          public void handle(Request request, Response response) {
            try {
              response.stream().output().write("good".getBytes());
            } catch (IOException e) {
              throw new IllegalStateException(e);
            }
          }
        });
      createNewDefaultAction(newController, "ping")
        .setPost(true)
        .setHandler(new RequestHandler() {
          @Override
          public void handle(Request request, Response response) {
            try {
              response.stream().output().write("pong".getBytes());
            } catch (IOException e) {
              throw new IllegalStateException(e);
            }
          }
        });
      createNewDefaultAction(newController, "fail")
        .setHandler(new RequestHandler() {
          @Override
          public void handle(Request request, Response response) {
            throw new IllegalStateException("Unexpected");
          }
        });
      createNewDefaultAction(newController, "fail_with_i18n_message")
        .setHandler(new RequestHandler() {
          @Override
          public void handle(Request request, Response response) {
            throw new BadRequestException("bad.request.reason", 0);
          }
        });
      createNewDefaultAction(newController, "fail_with_multiple_messages")
        .createParam("count", "Number of error messages to generate")
        .setHandler(new RequestHandler() {
          @Override
          public void handle(Request request, Response response) {
            Errors errors = new Errors();
            for (int count = 0; count < Integer.valueOf(request.param("count")); count++) {
              errors.add(Message.of("Bad request reason #" + count));
            }
            throw new BadRequestException(errors);
          }
        });
      createNewDefaultAction(newController, "fail_with_multiple_i18n_messages")
        .createParam("count", "Number of error messages to generate")
        .setHandler(new RequestHandler() {
          @Override
          public void handle(Request request, Response response) {
            Errors errors = new Errors();
            for (int count = 0; count < Integer.valueOf(request.param("count")); count++) {
              errors.add(Message.of("bad.request.reason", count));
            }
            throw new BadRequestException(errors);
          }
        });
      createNewDefaultAction(newController, "alive")
        .setHandler(new RequestHandler() {
          @Override
          public void handle(Request request, Response response) {
            response.noContent();
          }
        });

      createNewDefaultAction(newController, "fail_with_undeclared_parameter")
        .setHandler(new RequestHandler() {
          @Override
          public void handle(Request request, Response response) {
            response.newJsonWriter().prop("unknown", request.param("unknown"));
          }
        });

      // parameter "message" is required but not "author"
      NewAction print = createNewDefaultAction(newController, "print");
      print.createParam("message").setDescription("required message").setRequired(true);
      print.createParam("author").setDescription("optional author").setDefaultValue("-");
      print.createParam("format").setDescription("optional format").setPossibleValues("json", "xml");
      print.setHandler(new RequestHandler() {
        @Override
        public void handle(Request request, Response response) {
          try {
            request.param("format");
            IOUtils.write(
              request.mandatoryParam("message") + " by " + request.param("author", "nobody"), response.stream().output());
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        }
      });

      createNewDefaultAction(newController, "fail_with_client_abort_exception")
        .setHandler((request, response) -> {
          throw new IllegalStateException("fail!", new ClientAbortException());
        });

      newController.done();
    }

    private NewAction createNewDefaultAction(NewController controller, String key) {
      return controller
        .createAction(key)
        .setDescription("Dummy Description")
        .setSince("5.3")
        .setResponseExample(getClass().getResource("web-service-engine-test.txt"));
    }
  }
}
