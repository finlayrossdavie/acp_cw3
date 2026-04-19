# Resolves the Secrets Manager secret created in the console (e.g. name "cw3-secrets").
# JSON keys must match the names below so ECS can inject them as environment variables.

data "aws_secretsmanager_secret" "cw3" {
  name = var.secrets_manager_secret_name
}

locals {
  # Keys expected in the cw3-secrets JSON object (align with application.yml / .env).
  cw3_secret_env_keys = [
    "GUARDIAN_API_KEY",
    "OPEN_FEC_API_KEY",
  ]
}
