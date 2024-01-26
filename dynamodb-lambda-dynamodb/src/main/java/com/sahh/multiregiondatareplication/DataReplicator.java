package com.sahh.multiregiondatareplication;

import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;

import java.util.ArrayList;
import java.util.List;

public class DataReplicator implements RequestHandler<DynamodbEvent, List<String>> {

    private static final String DESTINATION_TABLE_NAME = "UserDB2";
    private static final String DESTINATION_REGION = "us-west-2";

    private final List<Table> replicas = new ArrayList<>();

    @Override
    public List<String> handleRequest(DynamodbEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("EVENT TYPE: " + event.getClass());
        var operationsFound = new ArrayList<String>();

        AmazonDynamoDB destinationClient = AmazonDynamoDBClientBuilder.standard().withRegion(DESTINATION_REGION).build();
        DynamoDB destinationDynamoDB = new DynamoDB(destinationClient);
        Table destinationTable = destinationDynamoDB.getTable(DESTINATION_TABLE_NAME);
        logger.log("Destination table: " + destinationTable.getTableName());
        replicas.add(destinationTable);

        for (DynamodbStreamRecord record : event.getRecords()) {
            String eventName = record.getEventName();
            logger.log("Event name: " + eventName);

            if ("INSERT".equals(eventName) || "MODIFY".equals(eventName)) {
                // Extract data from the stream record
                String name = record.getDynamodb().getNewImage().get("name").getS();
                String email = record.getDynamodb().getNewImage().get("email").getS();

                // Write the data to all replicas and wait for acknowledgements
                int majority = replicas.size() / 2 + 1;
                int acknowledgements = 0;
                for (Table replica : replicas) {
                    PutItemOutcome outcome = replica.putItem(new Item().withString("name", name).withString("email", email));
                    if (outcome != null) {
                        acknowledgements++;
                    }
                    if (acknowledgements >= majority) {
                        operationsFound.add("Data written to majority of replicas: " + name + ", " + email);
                        break;
                    }
                }

            } else if ("REMOVE".equals(eventName)) {
                // Extract the email from the stream record
                String email = record.getDynamodb().getKeys().get("email").getS();
                logger.log("Email: " + email);
                // Delete the item from the destination DynamoDB table based on email
                destinationTable.deleteItem(new KeyAttribute("email", email));
                operationsFound.add("Item removed from destination table based on email: " + email);
            }
        }
        return operationsFound;
    }
}
