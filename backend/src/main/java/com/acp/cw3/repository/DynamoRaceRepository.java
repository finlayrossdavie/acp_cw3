package com.acp.cw3.repository;

import com.acp.cw3.model.Race;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DynamoRaceRepository {
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final ObjectMapper objectMapper;

    public DynamoRaceRepository(DynamoDbClient dynamoDbClient,
                                @Value("${app.aws.table-name}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ensureTableExists();
    }

    public void upsert(Race race) {
        try {
            String payload = objectMapper.writeValueAsString(race);
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "raceId", AttributeValue.builder().s(race.raceId()).build(),
                            "state", AttributeValue.builder().s(race.state()).build(),
                            "payload", AttributeValue.builder().s(payload).build()
                    ))
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize race", e);
        }
    }

    public Optional<Race> findByRaceId(String raceId) {
        Map<String, AttributeValue> key = Map.of("raceId", AttributeValue.builder().s(raceId).build());
        var response = dynamoDbClient.getItem(GetItemRequest.builder().tableName(tableName).key(key).build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(deserialize(response.item().get("payload").s()));
    }

    public Optional<Race> findByState(String state) {
        var response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .indexName("state-index")
                .keyConditionExpression("#state = :state")
                .expressionAttributeNames(Map.of("#state", "state"))
                .expressionAttributeValues(Map.of(":state", AttributeValue.builder().s(state).build()))
                .limit(1)
                .build());
        if (!response.hasItems() || response.items().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(deserialize(response.items().get(0).get("payload").s()));
    }

    public List<Race> findAll() {
        var response = dynamoDbClient.scan(ScanRequest.builder().tableName(tableName).build());
        if (!response.hasItems()) {
            return Collections.emptyList();
        }
        return response.items().stream().map(item -> deserialize(item.get("payload").s())).toList();
    }

    private Race deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, Race.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize race", e);
        }
    }

    private void ensureTableExists() {
        try {
            dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
        } catch (ResourceNotFoundException ex) {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("raceId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("state").attributeType(ScalarAttributeType.S).build()
                    )
                    .keySchema(KeySchemaElement.builder().attributeName("raceId").keyType(KeyType.HASH).build())
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                            .indexName("state-index")
                            .keySchema(KeySchemaElement.builder().attributeName("state").keyType(KeyType.HASH).build())
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                            .build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        }
    }
}
