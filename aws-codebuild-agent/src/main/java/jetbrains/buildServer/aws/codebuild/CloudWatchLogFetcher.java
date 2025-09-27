package jetbrains.buildServer.aws.codebuild;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.*;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.util.amazon.AWSClients;
import jetbrains.buildServer.util.amazon.AWSCommonParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static jetbrains.buildServer.aws.codebuild.CodeBuildConstants.*;
import static jetbrains.buildServer.messages.DefaultMessagesInfo.*;

/**
 * Utility class for fetching and streaming CloudWatch logs to TeamCity build log
 * @author vbedrosova
 */
public class CloudWatchLogFetcher {
  
  @NotNull
  private final String projectName;
  @NotNull
  private final String buildId;
  @NotNull
  private final Map<String, String> params;
  @NotNull
  private final AtomicLong lastEventTime = new AtomicLong(0);
  @NotNull
  private final AtomicLong lastFetchTime = new AtomicLong(0);
  private volatile boolean hasLogs = false;
  private volatile boolean hasLoggedCredentialsError = false;
  
  public CloudWatchLogFetcher(@NotNull String projectName, @NotNull String buildId, @NotNull Map<String, String> params) {
    this.projectName = projectName;
    this.buildId = buildId;
    this.params = params;
  }
  
  /**
   * Fetches and streams new log events from CloudWatch to TeamCity build log
   * @param build The TeamCity build instance
   * @return true if new logs were found and streamed, false otherwise
   */
  public boolean fetchAndStreamLogs(@NotNull AgentRunningBuild build) {
    if (!isStreamLogsEnabled()) {
      debugLog(build, "Stream logs is disabled");
      return false;
    }
    
    debugLog(build, "Stream logs is enabled, attempting to fetch logs");
    
    // If we've already logged a credentials error, don't try again
    if (hasLoggedCredentialsError) {
      debugLog(build, "Credentials error already logged, skipping CloudWatch fetch");
      return false;
    }
    
    // Optimize: Use different intervals based on whether we've found logs
    long currentTime = System.currentTimeMillis();
    long interval = hasLogs ? LOG_FETCH_INTERVAL / 2 : LOG_FETCH_INTERVAL; // Fetch more frequently when logs are available
    if ((currentTime - lastFetchTime.get()) < interval) {
      debugLog(build, "Interval check failed, skipping fetch (interval: " + interval + "ms)");
      return false;
    }
    lastFetchTime.set(currentTime);
    
    debugLog(build, "Interval check passed, proceeding with fetch");
    
    try {
      return AWSCommonParams.withAWSClients(params, new AWSCommonParams.WithAWSClients<Boolean, RuntimeException>() {
        @Override
        public Boolean run(@NotNull AWSClients clients) throws RuntimeException {
          return fetchLogsFromCloudWatch(clients, build);
        }
      });
    } catch (Exception e) {
      // Only log the error once to avoid spam
      if (!hasLoggedCredentialsError) {
        build.getBuildLogger().message("Failed to fetch CloudWatch logs: " + e.getMessage() + ". CloudWatch log streaming disabled.", Status.WARNING);
        hasLoggedCredentialsError = true;
      }
      return false;
    }
  }
  
  private boolean fetchLogsFromCloudWatch(@NotNull AWSClients clients, @NotNull AgentRunningBuild build) {
    // AWS CodeBuild uses a single log group for all projects
    // The actual log group name is /aws/codebuild/ (with trailing slash)
    String logGroupName = "/aws/codebuild/";
    // The log stream name is projectName/buildId
    String logStreamName = projectName + "/" + buildId.replace(projectName + ":", "");
    
    debugLog(build, "Looking for log group: " + logGroupName + ", stream prefix: " + logStreamName);
    
    try {
      // Create CloudWatch Logs client using the same credentials as other AWS clients
      // We need to get the credentials from the existing CodeBuild client
      // Try CloudWatch-specific region first, then fall back to CodeBuild region
      String region = params.get(CLOUDWATCH_REGION_PARAM);
      if (region == null || region.trim().isEmpty()) {
        region = params.get(AWSCommonParams.REGION_NAME_PARAM);
      }
      if (region == null) {
        region = "us-east-1"; // Default region
      }
      
      debugLog(build, "Creating CloudWatch Logs client for region: " + region);
      
      // Get credentials from the existing AWS client configuration
      AWSLogs logsClient = createCloudWatchLogsClient(clients, region);
      
      debugLog(build, "CloudWatch Logs client created successfully");
      
      // Find the log stream
      DescribeLogStreamsRequest describeRequest = new DescribeLogStreamsRequest()
        .withLogGroupName(logGroupName)
        .withLogStreamNamePrefix(logStreamName);
      
      debugLog(build, "Calling DescribeLogStreams API with group: " + logGroupName + ", prefix: " + logStreamName);
      
      DescribeLogStreamsResult describeResult;
      try {
        describeResult = logsClient.describeLogStreams(describeRequest);
        debugLog(build, "DescribeLogStreams API call completed successfully, found " + describeResult.getLogStreams().size() + " streams");
      } catch (Exception e) {
        debugLog(build, "DescribeLogStreams API call failed: " + e.getMessage());
        return false;
      }
      
      if (describeResult.getLogStreams().isEmpty()) {
        debugLog(build, "No log streams found for group: " + logGroupName);
        return false;
      }
      
      // Get the most recent log stream
      LogStream logStream = describeResult.getLogStreams().get(0);
      String actualLogStreamName = logStream.getLogStreamName();
      
      debugLog(build, "Found log stream: " + actualLogStreamName);
      
      // Fetch log events
      GetLogEventsRequest getLogEventsRequest = new GetLogEventsRequest()
        .withLogGroupName(logGroupName)
        .withLogStreamName(actualLogStreamName)
        .withStartTime(lastEventTime.get())
        .withLimit(MAX_LOG_EVENTS_PER_REQUEST);
      
      debugLog(build, "Fetching events from stream: " + actualLogStreamName + ", startTime: " + lastEventTime.get());
      
      GetLogEventsResult logEventsResult = logsClient.getLogEvents(getLogEventsRequest);
      
      debugLog(build, "Found " + logEventsResult.getEvents().size() + " log events in stream: " + actualLogStreamName);
      
      if (logEventsResult.getEvents().isEmpty()) {
        // Try fetching from the beginning if we haven't found any events yet
        if (lastEventTime.get() == 0) {
          debugLog(build, "No events found, trying to fetch from beginning");
          GetLogEventsRequest retryRequest = new GetLogEventsRequest()
            .withLogGroupName(logGroupName)
            .withLogStreamName(actualLogStreamName)
            .withLimit(MAX_LOG_EVENTS_PER_REQUEST);
          
          GetLogEventsResult retryResult = logsClient.getLogEvents(retryRequest);
          if (!retryResult.getEvents().isEmpty()) {
            debugLog(build, "Found " + retryResult.getEvents().size() + " events from beginning, streaming to TeamCity");
            streamLogsToTeamCity(retryResult.getEvents(), build);
            
            // Update last event time
            OutputLogEvent lastEvent = retryResult.getEvents().get(retryResult.getEvents().size() - 1);
            lastEventTime.set(lastEvent.getTimestamp());
            hasLogs = true;
            return true;
          }
        }
        debugLog(build, "No log events found");
        return false;
      }
      
      // Stream logs to TeamCity
      debugLog(build, "Streaming " + logEventsResult.getEvents().size() + " events to TeamCity");
      streamLogsToTeamCity(logEventsResult.getEvents(), build);
      
      // Update last event time and mark that we have logs
      if (!logEventsResult.getEvents().isEmpty()) {
        OutputLogEvent lastEvent = logEventsResult.getEvents().get(logEventsResult.getEvents().size() - 1);
        lastEventTime.set(lastEvent.getTimestamp());
        hasLogs = true;
        debugLog(build, "Updated last event time to: " + lastEvent.getTimestamp());
      }
      
      return true;
      
    } catch (ResourceNotFoundException e) {
      // Log group or stream doesn't exist yet
      return false;
    } catch (Exception e) {
      build.getBuildLogger().message("Error fetching CloudWatch logs: " + e.getMessage(), Status.WARNING);
      return false;
    }
  }
  
  private void streamLogsToTeamCity(@NotNull List<OutputLogEvent> events, @NotNull AgentRunningBuild build) {
    debugLog(build, "Processing " + events.size() + " log events for streaming");
    
    for (OutputLogEvent event : events) {
      String logMessage = event.getMessage();
      if (logMessage != null && !logMessage.trim().isEmpty()) {
        // Handle progress updates and other special formatting
        String[] formattedMessages = formatLogMessage(logMessage);
        
        // Stream each formatted message to TeamCity build log
        for (String formattedMessage : formattedMessages) {
          if (formattedMessage != null && !formattedMessage.trim().isEmpty()) {
            debugLog(build, "Streaming message: " + formattedMessage);
            build.getBuildLogger().message(formattedMessage);
          }
        }
      }
    }
  }
  
  /**
   * Formats log messages to be more readable in TeamCity
   */
  private String[] formatLogMessage(String logMessage) {
    // Handle progress updates that use carriage returns
    if (logMessage.contains("\r")) {
      // Split by carriage returns and process each line
      String[] lines = logMessage.split("\r");
      java.util.List<String> result = new java.util.ArrayList<>();
      
      for (String line : lines) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty()) {
          // If it's a progress update, format it nicely
          if (trimmed.matches(".*\\d+%.*")) {
            result.add(trimmed);
          } else {
            result.add(trimmed);
          }
        }
      }
      
      // Return only the last few progress updates to avoid spam
      if (result.size() > 3) {
        return result.subList(Math.max(0, result.size() - 3), result.size()).toArray(new String[0]);
      }
      
      return result.toArray(new String[0]);
    }
    
    // Handle regular messages
    String formatted = logMessage.trim();
    
    // If line is too long (>120 characters), try to break it intelligently
    if (formatted.length() > 120) {
      // Try to break at common delimiters
      String[] breakPoints = {", ", " ", "=", ":", "|"};
      for (String breakPoint : breakPoints) {
        if (formatted.contains(breakPoint)) {
          // Find a good break point around 100 characters
          int targetLength = 100;
          int lastBreak = formatted.lastIndexOf(breakPoint, targetLength);
          if (lastBreak > 50) { // Don't break too early
            formatted = formatted.substring(0, lastBreak) + breakPoint + "\n  " + 
                       formatted.substring(lastBreak + breakPoint.length());
            break;
          }
        }
      }
    }
    
    return new String[]{formatted};
  }
  
  private boolean isStreamLogsEnabled() {
    return Boolean.parseBoolean(params.get(STREAM_LOGS_PARAM));
  }
  
  private boolean isDebugLogsEnabled() {
    return Boolean.parseBoolean(params.get(DEBUG_LOGS_PARAM));
  }
  
  private void debugLog(@NotNull AgentRunningBuild build, @NotNull String message) {
    if (isDebugLogsEnabled()) {
      build.getBuildLogger().message("CloudWatch Debug: " + message);
    }
  }
  
  @NotNull
  private AWSLogs createCloudWatchLogsClient(@NotNull AWSClients clients, @NotNull String region) {
    // Create a CloudWatch Logs client using the same credentials as other AWS clients
    // Since we're inside AWSCommonParams.withAWSClients, we should use the same credential context
    // Let's try to get the credentials from the existing CodeBuild client
    try {
      // Get the CodeBuild client to access its credentials
      Object codeBuildClient = clients.createCodeBuildClient();
      
      // Try to extract credentials using reflection
      java.lang.reflect.Method getCredentialsMethod = codeBuildClient.getClass().getMethod("getCredentialsProvider");
      AWSCredentialsProvider credentialsProvider = (AWSCredentialsProvider) getCredentialsMethod.invoke(codeBuildClient);
      
      return AWSLogsClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withRegion(region)
        .build();
    } catch (Exception e) {
      // If reflection fails, try to use the same credential chain as the CodeBuild client
      try {
        // Get the CodeBuild client and try to access its configuration
        Object codeBuildClient = clients.createCodeBuildClient();
        
        // Try to get the credentials from the client's configuration
        java.lang.reflect.Field credentialsField = codeBuildClient.getClass().getDeclaredField("awsCredentialsProvider");
        credentialsField.setAccessible(true);
        AWSCredentialsProvider credentialsProvider = (AWSCredentialsProvider) credentialsField.get(codeBuildClient);
        
        return AWSLogsClientBuilder.standard()
          .withCredentials(credentialsProvider)
          .withRegion(region)
          .build();
      } catch (Exception e2) {
        // Final fallback: use the default credential chain
        return AWSLogsClientBuilder.standard()
          .withRegion(region)
          .build();
      }
    }
  }
  
  /**
   * Gets the CloudWatch log group name for CodeBuild
   */
  @NotNull
  public static String getLogGroupName(@NotNull String projectName) {
    return "/aws/codebuild/";
  }
  
  /**
   * Gets the CloudWatch log stream name for the build
   */
  @NotNull
  public static String getLogStreamName(@NotNull String buildId, @NotNull String projectName) {
    return projectName + "/" + buildId.replace(projectName + ":", "");
  }
}
