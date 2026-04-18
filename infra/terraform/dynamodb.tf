resource "aws_dynamodb_table" "election_races" {
  name         = "${var.project_name}-ElectionRaces"
  billing_mode = "PAY_PER_REQUEST"

  hash_key = "raceId"

  attribute {
    name = "raceId"
    type = "S"
  }

  attribute {
    name = "state"
    type = "S"
  }

  global_secondary_index {
    name            = "state-index"
    hash_key        = "state"
    projection_type = "ALL"
  }

  tags = {
    Project = var.project_name
  }
}
