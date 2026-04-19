data "aws_caller_identity" "current" {}

output "ecr_repository_url" {
  description = "docker tag/push target for the backend image"
  value       = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com/${aws_ecr_repository.backend.name}"
}

output "alb_dns_name" {
  description = "Public ALB DNS — use as API base until a custom domain is attached"
  value       = aws_lb.api.dns_name
}

output "api_base_url_http" {
  description = "Use for VITE_API_BASE_URL when the ALB uses HTTP (port 80)"
  value       = "http://${aws_lb.api.dns_name}"
}

output "api_base_url_https" {
  description = "Use for VITE_API_BASE_URL when ACM is on the ALB (port 443). Prefers api_public_base_url if set (custom domain)."
  value = var.certificate_arn != "" ? (
    trimspace(var.api_public_base_url) != "" ? trimspace(var.api_public_base_url) : "https://${aws_lb.api.dns_name}"
  ) : "(configure certificate_arn)"
}

output "cloudfront_domain_name" {
  description = "CloudFront domain — primary app URL (HTTPS)"
  value       = aws_cloudfront_distribution.app.domain_name
}

output "cloudfront_url" {
  value = "https://${aws_cloudfront_distribution.app.domain_name}"
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.app.id
}

output "frontend_bucket_id" {
  value = aws_s3_bucket.frontend.id
}

output "dynamodb_table_name" {
  value = aws_dynamodb_table.election_races.name
}

output "redis_endpoint" {
  value     = local.redis_host
  sensitive = true
}
