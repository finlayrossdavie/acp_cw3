#!/bin/sh

awslocal dynamodb create-table \
  --table-name ElectionRaces \
  --attribute-definitions \
    AttributeName=raceId,AttributeType=S \
    AttributeName=state,AttributeType=S \
  --key-schema \
    AttributeName=raceId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --global-secondary-indexes \
    "[
      {
        \"IndexName\": \"state-index\",
        \"KeySchema\": [{\"AttributeName\":\"state\",\"KeyType\":\"HASH\"}],
        \"Projection\": {\"ProjectionType\":\"ALL\"}
      }
    ]" || true
