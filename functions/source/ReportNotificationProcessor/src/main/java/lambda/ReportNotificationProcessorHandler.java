package lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.stepfunctions.AWSStepFunctions;
import com.amazonaws.services.stepfunctions.AWSStepFunctionsClientBuilder;
import com.amazonaws.services.stepfunctions.model.StartExecutionRequest;
import com.amazonaws.services.stepfunctions.model.StartExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import utils.ReportProcessingFinishedNotification;
import utils.SPAPINotification;
import utils.StateMachineInput;

import java.util.UUID;

import static utils.Constants.NOTIFICATION_TYPE_REPORT_PROCESSING_FINISHED;
import static utils.Constants.REPORT_PROCESSING_FINAL_STATUSES;

public class ReportNotificationProcessorHandler implements RequestHandler<SQSEvent, String> {

    //Lambda Environment Variables
    private static final String STATE_MACHINE_ARN_ENV_VARIABLE = "STATE_MACHINE_ARN";

    @Override
    public String handleRequest(SQSEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("NotificationProcessor Lambda handler started");

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            logger.log(String.format("Received new notification: %s", message.getBody()));

            try {
                SPAPINotification notification = mapNotification(message.getBody());

                if (!NOTIFICATION_TYPE_REPORT_PROCESSING_FINISHED.equals(notification.getNotificationType())) {
                    logger.log(String.format("Notification type %s skipped", notification.getNotificationType()));
                    continue;
                }

                ReportProcessingFinishedNotification reportNotification = notification.getPayload().getReportProcessingFinishedNotification();

                if (REPORT_PROCESSING_FINAL_STATUSES.contains(reportNotification.getProcessingStatus())) {
                    logger.log("Starting state machine execution");
                    String executionArn = startStepFunctionsExecution(reportNotification);
                    logger.log(String.format("State machine successfully started. Execution arn: %s", executionArn));
                } else {
                    logger.log(String.format("Report processing status %s skipped", reportNotification.getProcessingStatus()));
                }
            } catch (JsonProcessingException e) {
                logger.log(String.format("Message body could not be mapped to a SP-API Notification: %s", e.getMessage()));
                continue;
            }
        }

        return "Finished processing incoming notifications";
    }

    private SPAPINotification mapNotification(String notificationBody) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        SPAPINotification notification = mapper.readValue(notificationBody, SPAPINotification.class);

        return notification;
    }

    private String startStepFunctionsExecution(ReportProcessingFinishedNotification reportNotification) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        StateMachineInput input = getStateMachineInput(reportNotification);
        String inputStr = mapper.writeValueAsString(input);

        StartExecutionRequest request = new StartExecutionRequest();
        request.setStateMachineArn(System.getenv(STATE_MACHINE_ARN_ENV_VARIABLE));
        request.setName(String.format("%s-%s-%s", reportNotification.getSellerId(), reportNotification.getReportId(), UUID.randomUUID()));
        request.setInput(inputStr);

        AWSStepFunctions stepFunctions = AWSStepFunctionsClientBuilder.defaultClient();
        StartExecutionResult result = stepFunctions.startExecution(request);

        return result.getExecutionArn();
    }

    private StateMachineInput getStateMachineInput(ReportProcessingFinishedNotification reportNotification) {
        return StateMachineInput.builder()
                .SellerId(reportNotification.getSellerId())
                .ReportId(reportNotification.getReportId())
                .ReportType(reportNotification.getReportType())
                .ProcessingStatus(reportNotification.getProcessingStatus())
                .ReportDocumentId(reportNotification.getReportDocumentId())
                .build();
    }
}
