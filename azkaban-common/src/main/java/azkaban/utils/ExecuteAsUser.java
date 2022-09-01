/*
 * Copyright 2011 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package azkaban.utils;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Logger;

import static azkaban.Constants.ConfigurationKeys.AZKABAN_SERVER_NATIVE_LIB_FOLDER;
import static azkaban.Constants.ConfigurationKeys.AZKABAN_NEED_SUDO;

/**
 * This is a wrapper over the binary executable execute-as-user. It provides a simple API to run
 * commands as another user while abstracting away the process logic, commandline handling, etc.
 */
public class ExecuteAsUser {

  private final static Logger log = Logger.getLogger(ExecuteAsUser.class);
  private final static String EXECUTE_AS_USER = "execute-as-user";

  private final static String ROOT = "root";

  private static final SecureRandom random = new SecureRandom();

  private final File binaryExecutable;

  /**
   * Construct the object
   *
   * @param nativeLibDirectory Absolute path to the native Lib Directory
   */
  public ExecuteAsUser(final String nativeLibDirectory) {
    this.binaryExecutable = new File(nativeLibDirectory, EXECUTE_AS_USER);
    validate();
  }

  private void validate() {
    if (!this.binaryExecutable.canExecute()) {
      throw new RuntimeException("Unable to execute execute-as-user binary. Invalid Path: "
          + this.binaryExecutable.getAbsolutePath());
    }
  }
  
  // executeWithProps: for sudo user
  public int executeWithProps(final String user, final List<String> command, Props props) throws IOException {
    List<String> genCommands = constructExecuteAsCommand(user, command, props);
    log.info("Command: " + genCommands);
    final Process process = new ProcessBuilder()
        .command(genCommands)
        .inheritIO()
        .start();

    int exitCode;
    try {
      exitCode = process.waitFor();
    } catch (final InterruptedException e) {
      log.error(e.getMessage(), e);
      exitCode = 1;
    }

    return exitCode;
  }

  /**
   * API to execute a command on behalf of another user.
   *
   * @param user The proxy user
   * @param command the list containing the program and its arguments
   * @return The return value of the shell command
   */
  public int execute(final String user, final List<String> command) throws IOException {
    return executeWithProps(user, command, Props.emptyProps);
  }

  private List<String> constructExecuteAsCommand(final String user, final List<String> command, final Props props) {
    final List<String> commandList = new ArrayList<>();
    final boolean needSudo = props.getBoolean(AZKABAN_NEED_SUDO);
    if (needSudo) {
      commandList.add("sudo");
    }
    commandList.add(this.binaryExecutable.getAbsolutePath());
    commandList.add(user);
    commandList.addAll(command);
    return commandList;
  }

  // for sudo user
  public static void deleteDirectory(File directory, Props props) throws IOException {
    final ExecuteAsUser executeAsUser = new ExecuteAsUser(
      props.getString(AZKABAN_SERVER_NATIVE_LIB_FOLDER));
        
    executeAsUser.executeWithProps(ROOT, Arrays.asList("rm", "-rf", directory.getAbsolutePath()), props);
  }

  // createTempFile: for sudo user
  public static File createTempFile(String prefix, String suffix, File directory, Props props) throws IOException {
    final boolean needSudo = props.getBoolean(AZKABAN_NEED_SUDO);
    if (!needSudo) {
      return File.createTempFile(prefix, suffix, directory);
    }
    
    long n = random.nextLong();
    String nus = Long.toUnsignedString(n);
    
    final ExecuteAsUser executeAsUser = new ExecuteAsUser(
      props.getString(AZKABAN_SERVER_NATIVE_LIB_FOLDER));
      
    String tempFileAbsolutePath = directory.getAbsolutePath() + File.separator + prefix + nus + suffix;
    executeAsUser.executeWithProps(ROOT, Arrays.asList("touch", tempFileAbsolutePath), props);
    return new File(tempFileAbsolutePath);
  }
}
