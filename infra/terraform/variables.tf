variable "aws_region" {
  type        = string
  description = "AWS region (e.g. us-east-1)"
  default     = "us-east-1"
}

variable "project_name" {
  type        = string
  description = "Prefix for resource names"
  default     = "cw3"
}

variable "backend_image" {
  type        = string
  description = "Full image URI pushed to ECR before first ECS deploy (e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com/cw3-backend:latest)"
}

variable "certificate_arn" {
  type        = string
  description = "ACM certificate ARN in the same region as the ALB for HTTPS (optional; leave empty for HTTP-only listener on port 80)"
  default     = ""
}

variable "api_public_base_url" {
  type        = string
  description = "Public HTTPS API base URL for the SPA (e.g. https://api.example.com) when using a custom hostname (ACM + DNS CNAME to ALB). If empty, api_base_url_https output uses the ALB DNS name."
  default     = ""
}

variable "cors_allowed_origins" {
  type        = string
  description = "Comma-separated origins for CORS (must include https://<cloudfront-domain> after deploy)"
  default     = ""
}

variable "frontend_bucket_name" {
  type        = string
  description = "Globally unique S3 bucket name for static frontend"
}

variable "secrets_manager_secret_name" {
  type        = string
  description = "Secrets Manager secret name (JSON object) for API keys and related env — see infra/README.md"
  default     = "cw3-secrets"
}
