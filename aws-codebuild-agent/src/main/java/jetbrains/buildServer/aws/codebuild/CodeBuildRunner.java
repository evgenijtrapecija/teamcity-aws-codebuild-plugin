

package jetbrains.buildServer.aws.codebuild;

import com.amazonaws.services.codebuild.model.*;
import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.RunBuildException;
import jetbrains.buildServer.agent.*;
import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.messages.ErrorData;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.util.*;
import jetbrains.buildServer.util.amazon.AWSClients;
import jetbrains.buildServer.util.amazon.AWSCommonParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipOutputStream;

import static jetbrains.buildServer.aws.codebuild.CodeBuildUtil.*;
import static jetbrains.buildServer.messages.DefaultMessagesInfo.*;

/**
 * @author vbedrosova
 */
public class CodeBuildRunner extends AgentLifeCycleAdapter implements AgentBuildRunner {
  @NotNull
  private final List<CodeBuildBuildContext> myCodeBuildBuilds = new CopyOnWriteArrayList<CodeBuildBuildContext>();

  public CodeBuildRunner(@NotNull EventDispatcher<AgentLifeCycleListener> eventDispatcher) {
    eventDispatcher.addListener(this);
  }

  @NotNull
  @Override
  public BuildProcess createBuildProcess(@NotNull final AgentRunningBuild runningBuild, @NotNull final BuildRunnerContext context) throws RunBuildException {
    return new SyncBuildProcessAdapter() {
      @NotNull
      @Override
      protected BuildFinishedStatus runImpl() throws RunBuildException {
        final Map<String, String> runnerParameters = validateParams();
        final String projectName = getProjectName(runnerParameters);
        final String buildId = AWSCommonParams.withAWSClients(runnerParameters, new AWSCommonParams.WithAWSClients<String, RunBuildException>() {
          @Nullable
          @Override
          public String run(@NotNull AWSClients clients) throws RunBuildException {
            return clients.createCodeBuildClient().startBuild(
              new StartBuildRequest()
                .withProjectName(projectName)
                .withSourceVersion(getSourceVersion(projectName))
                .withBuildspecOverride(getBuildSpec(runnerParameters))
                .withArtifactsOverride(getArtifacts())
                .withTimeoutInMinutesOverride(getTimeoutMinutesInt(runnerParameters))
                .withEnvironmentVariablesOverride(getEnvironmentVariables())).getBuild().getId();
          }
        });

        runningBuild.addSharedEnvironmentVariable(String.format(CodeBuildConstants.BUILD_ID_ENVIRONMENT_VARIABLE_FORMAT, context.getId()), buildId);

        final String region = runnerParameters.get(AWSCommonParams.REGION_NAME_PARAM);
        runningBuild.getBuildLogger().message(projectName + " build " + getBuildLink(buildId, region) + " started");
        runningBuild.getBuildLogger().message("View the entire log in the AWS CloudWatch console " + getBuildLogLink(buildId, projectName, region));

        final CodeBuildBuildContext c = new CodeBuildBuildContext(buildId, projectName, runnerParameters);
        if (isWaitStep(runnerParameters)) {
          startContext(c, runningBuild);
          try {
            while (!finished(c, runningBuild)) {
              if (isInterrupted()) {
                CodeBuildRunner.this.interrupt(c, runningBuild);
                break;
              }
              
              // Fetch and stream CloudWatch logs during monitoring
              try {
                c.logFetcher.fetchAndStreamLogs(runningBuild);
              } catch (Exception e) {
                log(runningBuild, forContext(c, createTextMessage("Failed to fetch CloudWatch logs: " + e.getMessage(), Status.WARNING)));
              }
              
              try {
                Thread.sleep(CodeBuildConstants.POLL_INTERVAL);
              } catch (InterruptedException e) {
                break;
              }
            }
          } finally {
            log(runningBuild, getBlockEnd(c));
          }
          // Return the actual build status from CodeBuild
          return getBuildFinishedStatus(c, runningBuild);
        } else if (isWaitBuild(runnerParameters)) {
          myCodeBuildBuilds.add(c);
        }
        return isInterrupted() ? BuildFinishedStatus.INTERRUPTED : BuildFinishedStatus.FINISHED_SUCCESS;
      }

      @Nullable
      private String getSourceVersion(@NotNull String projectName) throws RunBuildException {
        final Map<String, String> params = context.getRunnerParameters();
        if (isUseBuildRevision(params)) {
          final ProjectInfo project = getProject(params, projectName);
          if (project == null) {
            throw new RunBuildException("No AWS CodeBuild project " + projectName + " found. Please check the settings.");
          }
          if (SourceType.GITHUB.toString().equals(project.getSourceType())) {
            final String vcsRootId = runningBuild.getSharedConfigParameters().get(CodeBuildConstants.GIT_HUB_VCS_ROOT_ID_CONFIG_PARAM);

            if (StringUtil.isEmptyOrSpaces(vcsRootId) || CodeBuildConstants.UNKNOWN_GIT_HUB_VCS_ROOT_ID.equals(vcsRootId)) {
              throw new RunBuildException("Failed to find the GitHub VCS root ID and use it to resolve " + CodeBuildConstants.SOURCE_VERSION_LABEL + " AWS CodeBuild setting");
            }

            final String envVarName = "BUILD_VCS_NUMBER_" + vcsRootId.replaceAll("[^a-zA-Z0-9]", "_").toUpperCase();
            final String sourceVersion = context.getBuildParameters().getEnvironmentVariables().get(envVarName);

            if (StringUtil.isEmptyOrSpaces(sourceVersion)) {
              throw new RunBuildException("Can't use empty $" + envVarName + " environment variable value as " + CodeBuildConstants.SOURCE_VERSION_LABEL + " AWS CodeBuild setting");
            }

            runningBuild.getBuildLogger().message("Using $" + envVarName + " environment variable value " + sourceVersion + " as the AWS CodeBuild source version");
            return sourceVersion;

          } else if (SourceType.S3.toString().equals(project.getSourceType())) {
            final File revision = prepareRevision();
            if (revision == null) {
              throw new RunBuildException("Unable to upload sources to the AWS S3: build checkout directory " + runningBuild.getCheckoutDirectory() + " is empty");
            }
            try {
              return AWSCommonParams.withAWSClients(params, new AWSCommonParams.WithAWSClients<String, RuntimeException>() {
                @Nullable
                @Override
                public String run(@NotNull AWSClients clients) throws RuntimeException {
                  return clients.createS3Client().putObject(getBucketName(project.getSourceLocation()), getObjectKey(project.getSourceLocation()), revision).getVersionId();
                }
              });
            } finally {
              FileUtil.delete(revision);
            }
          } else {
            throw new RunBuildException(CodeBuildConstants.USE_BUILD_REVISION_LABEL + " setting is supported only for Amazon S3 and GitHub AWS CodeBuild project source provider and can't be combined with " + project.getSourceType() + " source provider");
          }
        } else {
          return CodeBuildUtil.getSourceVersion(params);
        }
      }

      @Nullable
      private File prepareRevision() throws RunBuildException {
        final File[] files = runningBuild.getCheckoutDirectory().listFiles();
        if (files == null || files.length == 0) return null;
        if (files.length == 1 && files[0].getName().endsWith(".zip")) {
          return files[0];
        } else {
          final File revision = new File(runningBuild.getBuildTempDirectory() + "/" + runningBuild.getCheckoutDirectory().getName() + ".zip");
          try {
            ArchiveUtil.packZip(runningBuild.getCheckoutDirectory(), new ZipOutputStream(new FileOutputStream(revision)));
          } catch (FileNotFoundException e) {
            throw new RunBuildException("Failed to package the checkout directory content", e);
          }
          return revision;
        }
      }

      @NotNull
      private Collection<EnvironmentVariable> getEnvironmentVariables() {
        runningBuild.getBuildLogger().message("Will pass filtered environment variables to the AWS CodeBuild");
        
        Collection<EnvironmentVariable> envVars = new ArrayList<>();
        Map<String, String> allEnvVars = context.getBuildParameters().getEnvironmentVariables();
        
        // Add useful TeamCity build variables
        addTeamCityBuildVariables(envVars, runningBuild);
        
        // Add filtered environment variables (exclude TeamCity internal variables)
        for (Map.Entry<String, String> entry : allEnvVars.entrySet()) {
          String varName = entry.getKey();
          String varValue = entry.getValue();
          
          // Skip empty or null values
          if (StringUtil.isEmptyOrSpaces(varValue)) {
            continue;
          }
          
          // Skip variables we've already added as TeamCity build variables
          if (isTeamCityBuildVariable(varName)) {
            continue;
          }
          
          // Skip TeamCity internal environment variables
          if (isTeamCityInternalEnvironmentVariable(varName)) {
            continue;
          }
          
          envVars.add(new EnvironmentVariable().withName(varName).withValue(varValue));
        }
        
        runningBuild.getBuildLogger().message("Passing " + envVars.size() + " environment variables to CodeBuild (filtered from " + allEnvVars.size() + " total)");
        return envVars;
      }
      
      private void addTeamCityBuildVariables(@NotNull Collection<EnvironmentVariable> envVars, @NotNull AgentRunningBuild runningBuild) {
        // Add essential TeamCity build information as environment variables
        Map<String, String> buildParams = runningBuild.getBuildParameters().getAllParameters();
        
        // Build identification
        addIfPresent(envVars, "TEAMCITY_BUILD_ID", buildParams.get("teamcity.build.id"));
        addIfPresent(envVars, "TEAMCITY_BUILD_NUMBER", buildParams.get("build.number"));
        addIfPresent(envVars, "TEAMCITY_BUILD_TYPE_ID", buildParams.get("teamcity.buildType.id"));
        addIfPresent(envVars, "TEAMCITY_BUILD_CONF_NAME", buildParams.get("teamcity.buildConfName"));
        
        // VCS information
        addIfPresent(envVars, "TEAMCITY_BUILD_BRANCH", buildParams.get("teamcity.build.branch"));
        addIfPresent(envVars, "TEAMCITY_BUILD_COMMIT", buildParams.get("build.vcs.number"));
        addIfPresent(envVars, "TEAMCITY_BUILD_REPOSITORY", buildParams.get("vcsroot.url"));
        
        // Build trigger information
        addIfPresent(envVars, "TEAMCITY_BUILD_TRIGGERED_BY", buildParams.get("teamcity.build.triggeredBy.username"));
        addIfPresent(envVars, "TEAMCITY_BUILD_TRIGGER_TYPE", buildParams.get("teamcity.build.triggeredBy.type"));
        
        // Build URLs
        addIfPresent(envVars, "TEAMCITY_BUILD_URL", buildParams.get("teamcity.serverUrl") + "/viewLog.html?buildId=" + buildParams.get("teamcity.build.id"));
        
        // Agent information
        addIfPresent(envVars, "TEAMCITY_AGENT_NAME", buildParams.get("agent.name"));
        addIfPresent(envVars, "TEAMCITY_AGENT_HOSTNAME", buildParams.get("agent.hostname"));
        
        // Build start time
        addIfPresent(envVars, "TEAMCITY_BUILD_START_TIME", buildParams.get("build.start.time"));
        
        // Note: We don't add custom build parameters as TC_* variables to avoid
        // passing TeamCity internal variables to CodeBuild
      }
      
      private void addIfPresent(@NotNull Collection<EnvironmentVariable> envVars, @NotNull String name, @Nullable String value) {
        if (StringUtil.isNotEmpty(value)) {
          envVars.add(new EnvironmentVariable().withName(name).withValue(value));
        }
      }
      
      private boolean isTeamCityBuildVariable(@NotNull String varName) {
        return varName.startsWith("TEAMCITY_") ||
               varName.startsWith("TC_PARAM_") ||
               varName.startsWith("TC_ENV_") ||
               varName.startsWith("TC_SYSTEM_");
      }
      
      private boolean isTeamCityInternalEnvironmentVariable(@NotNull String varName) {
        // Filter out TeamCity internal environment variables
        return varName.startsWith("TC_") ||
               varName.startsWith("TEAMCITY_") ||
               varName.startsWith("BUILD_") ||
               varName.startsWith("BUILD_VCS_") ||
               varName.startsWith("BUILD_NUMBER") ||
               varName.startsWith("BUILD_TYPE") ||
               varName.startsWith("BUILD_CONFIGURATION") ||
               varName.startsWith("BUILD_AGENT") ||
               varName.startsWith("BUILD_SERVER") ||
               varName.startsWith("BUILD_USER") ||
               varName.startsWith("BUILD_TRIGGER") ||
               varName.startsWith("BUILD_REPOSITORY") ||
               varName.startsWith("BUILD_BRANCH") ||
               varName.startsWith("BUILD_COMMIT") ||
               varName.startsWith("BUILD_TAG") ||
               varName.startsWith("BUILD_URL") ||
               varName.startsWith("BUILD_ID") ||
               varName.startsWith("BUILD_NAME") ||
               varName.startsWith("BUILD_STATUS") ||
               varName.startsWith("BUILD_START") ||
               varName.startsWith("BUILD_FINISH") ||
               varName.startsWith("BUILD_DURATION") ||
               varName.startsWith("BUILD_ARTIFACTS") ||
               varName.startsWith("BUILD_PROPERTIES") ||
               varName.startsWith("BUILD_PARAMETERS") ||
               varName.startsWith("BUILD_RUNNER") ||
               varName.startsWith("BUILD_STEP") ||
               varName.startsWith("BUILD_AGENT_") ||
               varName.startsWith("BUILD_SERVER_") ||
               varName.startsWith("BUILD_USER_") ||
               varName.startsWith("BUILD_TRIGGER_") ||
               varName.startsWith("BUILD_REPOSITORY_") ||
               varName.startsWith("BUILD_BRANCH_") ||
               varName.startsWith("BUILD_COMMIT_") ||
               varName.startsWith("BUILD_TAG_") ||
               varName.startsWith("BUILD_URL_") ||
               varName.startsWith("BUILD_ID_") ||
               varName.startsWith("BUILD_NAME_") ||
               varName.startsWith("BUILD_STATUS_") ||
               varName.startsWith("BUILD_START_") ||
               varName.startsWith("BUILD_FINISH_") ||
               varName.startsWith("BUILD_DURATION_") ||
               varName.startsWith("BUILD_ARTIFACTS_") ||
               varName.startsWith("BUILD_PROPERTIES_") ||
               varName.startsWith("BUILD_PARAMETERS_") ||
               varName.startsWith("BUILD_RUNNER_") ||
               varName.startsWith("BUILD_STEP_") ||
               varName.startsWith("AWS_CODEBUILD_") || // Our own build ID variables
               // Additional system environment variables to filter
               varName.equals("SERVER_URL") ||
               varName.equals("_") ||
               varName.equals("SHLVL") ||
               varName.equals("OLDPWD") ||
               varName.equals("HOSTNAME") ||
               varName.equals("LANGUAGE") ||
               varName.equals("ASPNETCORE_URLS") ||
               varName.equals("CONFIG_FILE") ||
               varName.equals("PATH") ||
               varName.equals("HOME") ||
               varName.equals("USER") ||
               varName.equals("SHELL") ||
               varName.equals("PWD") ||
               varName.equals("LANG") ||
               varName.equals("LC_ALL") ||
               varName.equals("TMPDIR") ||
               varName.equals("TMP") ||
               varName.equals("TEMP") ||
               varName.equals("JAVA_HOME") ||
               varName.equals("NUGET_XMLDOC_MODE") ||
               varName.equals("JAVA_OPTS") ||
               varName.equals("JRE_HOME") ||
               varName.equals("CLASSPATH") ||
               varName.equals("JAVA_TOOL_OPTIONS") ||
               varName.equals("MAVEN_HOME") ||
               varName.equals("GRADLE_HOME") ||
               varName.equals("NODE_HOME") ||
               varName.equals("NPM_CONFIG_PREFIX") ||
               varName.equals("PYTHON_HOME") ||
               varName.equals("PYTHONPATH") ||
               varName.equals("GOPATH") ||
               varName.equals("GOROOT") ||
               varName.equals("DOTNET_HOME") ||
               varName.equals("MSBUILD_HOME") ||
               varName.equals("VISUAL_STUDIO_HOME") ||
               varName.equals("WINDIR") ||
               varName.equals("SYSTEMROOT") ||
               varName.equals("PROGRAMFILES") ||
               varName.equals("PROGRAMFILES(X86)") ||
               varName.equals("APPDATA") ||
               varName.equals("LOCALAPPDATA") ||
               varName.equals("TEMP") ||
               varName.equals("TMP") ||
               varName.equals("USERPROFILE") ||
               varName.equals("HOMEDRIVE") ||
               varName.equals("HOMEPATH") ||
               varName.equals("COMPUTERNAME") ||
               varName.equals("USERNAME") ||
               varName.equals("USERDOMAIN") ||
               varName.equals("LOGONSERVER") ||
               varName.equals("SESSIONNAME") ||
               varName.equals("PROCESSOR_ARCHITECTURE") ||
               varName.equals("PROCESSOR_IDENTIFIER") ||
               varName.equals("NUMBER_OF_PROCESSORS") ||
               varName.equals("OS") ||
               varName.equals("COMSPEC") ||
               varName.equals("PATHEXT") ||
               varName.equals("WINDIR") ||
               varName.equals("SYSTEMROOT") ||
               varName.startsWith("DOTNET_") ||
               varName.startsWith("JDK_") ||
               varName.startsWith("GIT_") ||
               varName.startsWith("NODE_") ||
               varName.startsWith("NPM_") ||
               varName.startsWith("YARN_") ||
               varName.startsWith("PYTHON_") ||
               varName.startsWith("PIP_") ||
               varName.startsWith("CONDA_") ||
               varName.startsWith("ANACONDA_") ||
               varName.startsWith("GO_") ||
               varName.startsWith("RUST_") ||
               varName.startsWith("CARGO_") ||
               varName.startsWith("MAVEN_") ||
               varName.startsWith("GRADLE_") ||
               varName.startsWith("SBT_") ||
               varName.startsWith("ANT_") ||
               varName.startsWith("IVY_") ||
               varName.startsWith("SBT_") ||
               varName.startsWith("SCALA_") ||
               varName.startsWith("KOTLIN_") ||
               varName.startsWith("ANDROID_") ||
               varName.startsWith("SDK_") ||
               varName.startsWith("NDK_") ||
               varName.startsWith("XCODE_") ||
               varName.startsWith("IOS_") ||
               varName.startsWith("MACOS_") ||
               varName.startsWith("DOCKER_") ||
               varName.startsWith("KUBERNETES_") ||
               varName.startsWith("K8S_") ||
               varName.startsWith("HELM_") ||
               varName.startsWith("TERRAFORM_") ||
               varName.startsWith("ANSIBLE_") ||
               varName.startsWith("CHEF_") ||
               varName.startsWith("PUPPET_") ||
               varName.startsWith("VAGRANT_") ||
               varName.startsWith("VIRTUALBOX_") ||
               varName.startsWith("VMWARE_") ||
               varName.startsWith("HYPERV_") ||
               varName.startsWith("AZURE_") ||
               varName.startsWith("GCP_") ||
               varName.startsWith("AWS_") ||
               varName.startsWith("GOOGLE_") ||
               varName.startsWith("MICROSOFT_") ||
               varName.startsWith("ORACLE_") ||
               varName.startsWith("IBM_") ||
               varName.startsWith("REDHAT_") ||
               varName.startsWith("SUSE_") ||
               varName.startsWith("UBUNTU_") ||
               varName.startsWith("DEBIAN_") ||
               varName.startsWith("CENTOS_") ||
               varName.startsWith("FEDORA_") ||
               varName.startsWith("ARCH_") ||
               varName.startsWith("FREEBSD_") ||
               varName.startsWith("SOLARIS_") ||
               varName.startsWith("ARDUINO_") ||
               varName.startsWith("RASPBERRY_") ||
               varName.startsWith("BEAGLEBONE_") ||
               varName.startsWith("INTEL_") ||
               varName.startsWith("AMD_") ||
               varName.startsWith("NVIDIA_") ||
               varName.startsWith("ARM_") ||
               varName.startsWith("X86_") ||
               varName.startsWith("X64_") ||
               varName.startsWith("AMD64_") ||
               varName.startsWith("AARCH64_") ||
               varName.startsWith("ARM64_") ||
               varName.equals("TZ") ||
               varName.startsWith("TZ_");
      }
      

      @Nullable
      private ProjectArtifacts getArtifacts() {
        final Map<String, String> params = context.getRunnerParameters();
        return isUploadS3Artifacts(params) ?
          new ProjectArtifacts()
            .withType(ArtifactsType.S3)
            .withPackaging(isZipS3Artifacts(params) ? ArtifactPackaging.ZIP : ArtifactPackaging.NONE)
            .withName(getArtifactS3Name(params))
            .withLocation(getArtifactS3Bucket(params))
          : null;
      }

      @NotNull
      private Map<String, String> validateParams() throws RunBuildException {
        final Map<String, String> runnerParameters = context.getRunnerParameters();
        final Map<String, String> invalids = ParametersValidator.validateSettings(runnerParameters, false);
        if (invalids.isEmpty()) return runnerParameters;
        throw new RunBuildException(StringUtil.join(invalids.values(), "\n"), null, ErrorData.BUILD_RUNNER_ERROR_TYPE);
      }
    };
  }

  private void startContext(@NotNull CodeBuildBuildContext c, @NotNull AgentRunningBuild runningBuild) {
    log(runningBuild, getBlockStart(c));
    log(runningBuild, forContext(c, createTextMessage("Waiting for build " + c.codeBuildBuildId + " finish")));
  }

  @NotNull
  private BuildMessage1 getBlockStart(@NotNull CodeBuildBuildContext c) {
    return forContext(c, createBlockStart(c.codeBuildProjectName, BLOCK_TYPE_TARGET));
  }

  @NotNull
  private BuildMessage1 getBlockEnd(@NotNull CodeBuildBuildContext c) {
    return forContext(c, createBlockEnd(c.codeBuildProjectName, BLOCK_TYPE_TARGET));
  }

  @NotNull
  private BuildMessage1 forContext(@NotNull CodeBuildBuildContext c, @NotNull BuildMessage1 m) {
    return m.updateFlowId(c.codeBuildBuildId);
  }

  private void log(@NotNull AgentRunningBuild b, @NotNull BuildMessage1 m) {
    b.getBuildLogger().getFlowLogger(m.getFlowId()).logMessage(m);
  }

  @NotNull
  @Override
  public AgentBuildRunnerInfo getRunnerInfo() {
    return new AgentBuildRunnerInfo() {
      @NotNull
      @Override
      public String getType() {
        return CodeBuildConstants.RUNNER_TYPE;
      }

      @Override
      public boolean canRun(@NotNull BuildAgentConfiguration agentConfiguration) {
        return true;
      }
    };
  }

  @Override
  public void buildStarted(@NotNull AgentRunningBuild runningBuild) {
    super.buildStarted(runningBuild);
    myCodeBuildBuilds.clear();
  }

  @Override
  public void beforeBuildFinish(@NotNull AgentRunningBuild build, @NotNull BuildFinishedStatus buildStatus) {
    super.beforeBuildFinish(build, buildStatus);

    for (CodeBuildBuildContext c : myCodeBuildBuilds) {
      startContext(c, build);
    }

    while (!myCodeBuildBuilds.isEmpty()) {
      for (CodeBuildBuildContext next : new ArrayList<CodeBuildBuildContext>(myCodeBuildBuilds)) {
        final boolean buildInterrupted = build.getInterruptReason() != null;
        boolean finished = false;
        try {
          finished = buildInterrupted || finished(next, build);
          if (buildInterrupted) {
            interrupt(next, build);
          }
        } finally {
          if (finished) {
            myCodeBuildBuilds.remove(next);
            log(build, getBlockEnd(next));
            // Note: Build status is automatically handled by the BuildFinishedStatus return value
          }
        }
      }
      if (build.getInterruptReason() == null) {
        try {
          Thread.sleep(CodeBuildConstants.POLL_INTERVAL);
        } catch (InterruptedException e) {
          break;
        }
      }
    }
    myCodeBuildBuilds.clear();
  }

  private boolean finished(@NotNull final CodeBuildBuildContext c, @NotNull AgentRunningBuild build) {
    final List<Build> builds = AWSCommonParams.withAWSClients(c.params, new AWSCommonParams.WithAWSClients<List<Build>, RuntimeException>() {
      @Nullable
      @Override
      public List<Build> run(@NotNull AWSClients clients) throws RuntimeException {
        return clients.createCodeBuildClient().batchGetBuilds(new BatchGetBuildsRequest().withIds(c.codeBuildBuildId)).getBuilds();
      }
    });

    if (builds == null || builds.isEmpty()) {
      log(build, forContext(c, createTextMessage("No AWS CodeBuild build with id=" + c.codeBuildBuildId + " found", Status.WARNING)));
      return true;
    }

    if (builds.size() > 1) {
      log(build, forContext(c, createTextMessage("Found several AWS CodeBuild builds with id=" + c.codeBuildBuildId + ". Will process the first one.", Status.WARNING)));
    }

    final Build codeBuildBuild = builds.iterator().next();

    reportPhases(codeBuildBuild, c, build);
    
    // Fetch and stream CloudWatch logs
    try {
      c.logFetcher.fetchAndStreamLogs(build);
    } catch (Exception e) {
      log(build, forContext(c, createTextMessage("Failed to fetch CloudWatch logs: " + e.getMessage(), Status.WARNING)));
    }

    if (codeBuildBuild.getBuildComplete()) {
      // Final log fetch before completion
      try {
        c.logFetcher.fetchAndStreamLogs(build);
      } catch (Exception e) {
        log(build, forContext(c, createTextMessage("Failed to fetch final CloudWatch logs: " + e.getMessage(), Status.WARNING)));
      }
      
      final String format = getBuildString(c) + " %s " + getBuildLink(c.codeBuildBuildId, c.params.get(AWSCommonParams.REGION_NAME_PARAM));
      final String status = codeBuildBuild.getBuildStatus();
      if (isSucceeded(status)) {
        log(build, (forContext(c, createTextMessage(String.format(format, "succeeded")))));
      } else {
        log(build, (forContext(c, createTextMessage(String.format(format, isFailed(status) ? "failed" : "finished with status " + status), Status.ERROR))));
      }
      return true;
    }
    return false;
  }

  private void interrupt(@NotNull final CodeBuildBuildContext c, @NotNull AgentRunningBuild build) {
    log(build, forContext(c, createTextMessage("Stopping " + getBuildString(c), Status.WARNING)));
    AWSCommonParams.withAWSClients(c.params, new AWSCommonParams.WithAWSClients<Void, RuntimeException>() {
      @Nullable
      @Override
      public Void run(@NotNull AWSClients clients) throws RuntimeException {
        clients.createCodeBuildClient().stopBuild(new StopBuildRequest().withId(c.codeBuildBuildId));
        return null;
      }
    });
  }

  @NotNull
  private static String getBuildString(@NotNull CodeBuildBuildContext c) {
    return "Build " + c.codeBuildBuildId;
  }

  private void reportPhases(@NotNull Build codeBuildBuild, @NotNull CodeBuildBuildContext c, @NotNull AgentRunningBuild build) {
    if (codeBuildBuild.getPhases() == null || codeBuildBuild.getPhases().size() <= c.prevPhases.size()) return;

    for (BuildPhase phase : codeBuildBuild.getPhases()) {
      final String phaseName = phase.getPhaseType();
      if (isPhaseReported(phaseName, c)) continue;

      final String format = getFormat(phase, phaseName);
      final String status = phase.getPhaseStatus();
      if (status == null || isInProgress(status)) {
        if (c.prevPhases.get(phaseName) == null) { // not yet reported
          log(build, forContext(c, createProgressMessage(String.format(format, "in progress")).updateTags(DefaultMessagesInfo.TAG_INTERNAL)));
        }
        c.prevPhases.put(phaseName, status);
      } else {
        c.prevPhases.put(phaseName, status);

        if (isSucceeded(status)) {
          log(build, forContext(c, createTextMessage(String.format(format, "succeeded"))));
        } else {
          log(build, forContext(c, createTextMessage(String.format(format, isFailed(status) ? "failed" : "finished with status " + status), Status.ERROR)));
          log(build, forContext(c, createBuildProblemMessage(createBuildProblem(phase, c.params, build.getCheckoutDirectory().getAbsolutePath()))));
        }
      }
    }
  }

  private boolean isPhaseReported(@NotNull String phaseName, @NotNull CodeBuildBuildContext c) {
    final String status = c.prevPhases.get(phaseName);
    return status != null && !isInProgress(status);
  }

  @NotNull
  private String getFormat(@NotNull BuildPhase phase, @NotNull String phaseName) {
    final Long phaseDuration = phase.getDurationInSeconds();
    return phaseDuration == null ?
      phaseName + " %s" :
      phaseName + " %s in " + phaseDuration + StringUtil.pluralize(" seconds", phaseDuration.intValue());
  }

  @NotNull
  private BuildProblemData createBuildProblem(@NotNull BuildPhase failedPhase, @NotNull Map<String, String> runnerParams, @NotNull String checkoutDir) {
    return BuildProblemData.createBuildProblem(
      getProblemIdentity(checkoutDir, failedPhase, runnerParams),
      CodeBuildConstants.BUILD_PROBLEM_TYPE,
      getProblemDescription(failedPhase, runnerParams));
  }

  @NotNull
  private String getProblemDescription(@NotNull BuildPhase failedPhase, @NotNull Map<String, String> runnerParams) {
    final StringBuilder res = new StringBuilder(getProjectName(runnerParams));
    res.append(" ").append(failedPhase.getPhaseType()).append(" phase ");
    if (failedPhase.getContexts().isEmpty()) {
      if (isFailed(failedPhase.getPhaseStatus())) {
        res.append("failed");
      } else {
        res.append("finished with status: ").append(failedPhase.getPhaseStatus());
      }
    } else {
      res.append(": ");
      for (int i = 0; i < failedPhase.getContexts().size(); ++i) {
        if (i > 0) {
          res.append("; ");
        }
        res.append(failedPhase.getContexts().get(i).getMessage());
      }
    }
    return res.toString();
  }


  @NotNull
  private String getProblemIdentity(@NotNull String checkoutDir, @NotNull BuildPhase failedPhase, @NotNull Map<String, String> runnerParams) {
    final ArrayList<String> otherParts = new ArrayList<String>();
    otherParts.add(getProjectName(runnerParams));
    otherParts.add(failedPhase.getPhaseType());
    otherParts.add(failedPhase.getPhaseStatus());
    for (PhaseContext phaseContext : failedPhase.getContexts()) {
      if (StringUtil.isNotEmpty(phaseContext.getStatusCode())) otherParts.add(phaseContext.getStatusCode());
      if (StringUtil.isNotEmpty(phaseContext.getMessage())) otherParts.add(phaseContext.getMessage());
    }
    return String.valueOf(AWSCommonParams.calculateIdentity(checkoutDir, runnerParams, otherParts));
  }

  @NotNull
  private BuildFinishedStatus getBuildFinishedStatus(@NotNull final CodeBuildBuildContext c, @NotNull AgentRunningBuild build) {
    final List<Build> builds = AWSCommonParams.withAWSClients(c.params, new AWSCommonParams.WithAWSClients<List<Build>, RuntimeException>() {
      @Nullable
      @Override
      public List<Build> run(@NotNull AWSClients clients) throws RuntimeException {
        return clients.createCodeBuildClient().batchGetBuilds(new BatchGetBuildsRequest().withIds(c.codeBuildBuildId)).getBuilds();
      }
    });

    if (builds == null || builds.isEmpty()) {
      log(build, forContext(c, createTextMessage("No AWS CodeBuild build with id=" + c.codeBuildBuildId + " found", Status.WARNING)));
      return BuildFinishedStatus.FINISHED_WITH_PROBLEMS;
    }

    final Build codeBuildBuild = builds.iterator().next();
    final String status = codeBuildBuild.getBuildStatus();
    
    if (isSucceeded(status)) {
      return BuildFinishedStatus.FINISHED_SUCCESS;
    } else if (isFailed(status)) {
      return BuildFinishedStatus.FINISHED_WITH_PROBLEMS;
    } else {
      // For other statuses like TIMED_OUT, STOPPED, etc.
      return BuildFinishedStatus.FINISHED_WITH_PROBLEMS;
    }
  }

  private static final class CodeBuildBuildContext {
    @NotNull private final String codeBuildBuildId;
    @NotNull private final String codeBuildProjectName;
    @NotNull private final Map<String, String> params;
    @NotNull private Map<String, String> prevPhases = new HashMap<String, String>();
    @NotNull private final CloudWatchLogFetcher logFetcher;

    private CodeBuildBuildContext(@NotNull String codeBuildBuildId, @NotNull String codeBuildProjectName, @NotNull Map<String, String> params) {
      this.codeBuildBuildId = codeBuildBuildId;
      this.codeBuildProjectName = codeBuildProjectName;
      this.params = params;
      this.logFetcher = new CloudWatchLogFetcher(codeBuildProjectName, codeBuildBuildId, params);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      CodeBuildBuildContext that = (CodeBuildBuildContext) o;

      return codeBuildBuildId.equals(that.codeBuildBuildId);
    }

    @Override
    public int hashCode() {
      return codeBuildBuildId.hashCode();
    }
  }
}