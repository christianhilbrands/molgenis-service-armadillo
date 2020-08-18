package org.molgenis.armadillo;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.function.Function;
import org.molgenis.armadillo.exceptions.ArmadilloSessionException;
import org.molgenis.armadillo.service.ArmadilloConnectionFactory;
import org.molgenis.r.service.ProcessService;
import org.rosuda.REngine.Rserve.RConnection;
import org.rosuda.REngine.Rserve.RSession;
import org.rosuda.REngine.Rserve.RserveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArmadilloSession {
  private RSession rSession = null;

  private static final Logger logger = LoggerFactory.getLogger(ArmadilloSession.class);

  private final ArmadilloConnectionFactory connectionFactory;
  private final ProcessService processService;
  private Integer pid;

  public ArmadilloSession(
      ArmadilloConnectionFactory connectionFactory, ProcessService processService) {
    this.connectionFactory = requireNonNull(connectionFactory);
    this.processService = processService;
  }

  public synchronized <T> T execute(Function<RConnection, T> consumer) {
    RConnection connection = getRConnection();
    try {
      return consumer.apply(connection);
    } finally {
      tryDetachRConnection(connection);
    }
  }

  @SuppressWarnings("java:S3011")
  private Optional<Integer> getPort() {
    if (rSession == null) {
      return Optional.empty();
    }
    try {
      var portField = RSession.class.getDeclaredField("port");
      portField.setAccessible(true);
      return Optional.of(portField.getInt(rSession));
    } catch (NoSuchFieldException | IllegalAccessException e) {
      return Optional.empty();
    }
  }

  void tryDetachRConnection(RConnection connection) {
    try {
      rSession = connection.detach();
      if (logger.isDebugEnabled()) {
        logger.debug("Detached session, port = {}", getPort());
      }
    } catch (RserveException e) {
      logger.error("Failed to detach connection", e);
      rSession = null;
    }
  }

  RConnection getRConnection() {
    try {
      if (rSession == null) {
        var connection = connectionFactory.createConnection();
        pid = processService.getPid(connection);
        return connection;
      }
      var connection = rSession.attach();
      rSession = null;
      return connection;
    } catch (RserveException err) {
      throw new ArmadilloSessionException("Could not attach connection to RSession", err);
    }
  }

  public void sessionCleanup() {
    try {
      if (rSession != null) {
        logger.debug("Cleanup session");
        RConnection connection = rSession.attach();
        connection.close();
      } else if (pid != null) {
        var connection = connectionFactory.createConnection();
        try {
          processService.terminateProcess(connection, pid);
        } finally {
          connection.close();
        }
      }
    } catch (RserveException err) {
      throw new ArmadilloSessionException("Closing session and/or connection failed", err);
    }
  }
}
