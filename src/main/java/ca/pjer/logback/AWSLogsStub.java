package ca.pjer.logback;

import ca.pjer.logback.metrics.AwsLogsMetricsHolder;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.*;

import java.util.*;

class AWSLogsStub {
    private final Comparator<InputLogEvent> inputLogEventByTimestampComparator = Comparator.comparing(InputLogEvent::getTimestamp);
    private final String logGroupName;
    private final String logStreamName;
    private final String logRegion;
    private final String cloudWatchEndpoint;
    private final boolean verbose;
    private String sequenceToken;
    private Long lastTimestamp;
    private int retentionTimeInDays;

    private final Lazy<AWSLogs> lazyAwsLogs = new Lazy<>();

    AWSLogsStub(String logGroupName, String logStreamName, String logRegion, int retentionTimeInDays, String cloudWatchEndpoint, boolean verbose) {
        this.logGroupName = logGroupName;
        this.logStreamName = logStreamName;
        this.logRegion = logRegion;
        this.retentionTimeInDays = retentionTimeInDays;
        this.cloudWatchEndpoint = cloudWatchEndpoint;
        this.verbose = verbose;
    }


    private AWSLogs awsLogs() {
        return lazyAwsLogs.getOrCompute(() -> {
            if (verbose) {
                System.out.println("Creating AWSLogs Client");
            }
            AWSLogsClientBuilder builder = AWSLogsClientBuilder.standard();

            if (Objects.nonNull(cloudWatchEndpoint)) {
                AwsClientBuilder.EndpointConfiguration endpointConfiguration = new AwsClientBuilder.EndpointConfiguration(
                        cloudWatchEndpoint, logRegion);
                ClientConfiguration cc  = new Object();
                builder.setEndpointConfiguration(endpointConfiguration);
                builder.setClientConfiguration(cc);
            } else {
                Optional.ofNullable(logRegion).ifPresent(builder::setRegion);
            }

            AWSLogs awsLogs = builder.build();
            initLogGroup(awsLogs);
            return awsLogs;
        });
    }

    private void initLogGroup(AWSLogs awsLogs) {
        try {
            awsLogs.createLogGroup(new CreateLogGroupRequest().withLogGroupName(logGroupName));
            if(retentionTimeInDays > 0) {
                awsLogs.putRetentionPolicy(new PutRetentionPolicyRequest()
                        .withLogGroupName(logGroupName)
                        .withRetentionInDays(retentionTimeInDays));
            }
        } catch (ResourceAlreadyExistsException e) {
            // ignore
        } catch (Throwable t) {
            if (verbose) {
                t.printStackTrace();
            }
        }
        try {
            awsLogs.createLogStream(new CreateLogStreamRequest().withLogGroupName(logGroupName).withLogStreamName(logStreamName));
        } catch (ResourceAlreadyExistsException e) {
            // ignore
        } catch (Throwable t) {
            if (verbose) {
                t.printStackTrace();
            }
        }
    }

    synchronized void start() {
    }

    synchronized void stop() {
        try {
            awsLogs().shutdown();
        } catch (Exception e) {
            // ignore
        }
    }

    synchronized void logEvents(Collection<InputLogEvent> events) {
        if (events.size() > 1) {
            List<InputLogEvent> sortedEvents = new ArrayList<InputLogEvent>(events);
            Collections.sort(sortedEvents, inputLogEventByTimestampComparator);
            events = sortedEvents;
        }
        for (InputLogEvent event : events) {
            if (lastTimestamp != null && event.getTimestamp() < lastTimestamp) {
                event.setTimestamp(lastTimestamp);
            } else {
                lastTimestamp = event.getTimestamp();
            }
        }
        AwsLogsMetricsHolder.get().incrementLogEvents(events.size());
        AwsLogsMetricsHolder.get().incrementPutLog();
        logPreparedEvents(events);
    }

    private void logPreparedEvents(Collection<InputLogEvent> events) {
        try {
            PutLogEventsRequest request = new PutLogEventsRequest()
                    .withLogGroupName(logGroupName)
                    .withLogStreamName(logStreamName)
                    .withSequenceToken(sequenceToken)
                    .withLogEvents(events);
            PutLogEventsResult result = awsLogs().putLogEvents(request);
            sequenceToken = result.getNextSequenceToken();
        } catch (DataAlreadyAcceptedException e) {
            sequenceToken = e.getExpectedSequenceToken();
        } catch (InvalidSequenceTokenException e) {
            sequenceToken = e.getExpectedSequenceToken();
            logPreparedEvents(events);
        } catch (Throwable t) {
            if (verbose) {
                t.printStackTrace();
            }
            AwsLogsMetricsHolder.get().incrementPutLogFailed(t);
            throw t;
        }
    }
}
