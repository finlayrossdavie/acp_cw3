# AWS deployment (Terraform + GitHub Actions)

This stack provisions:

- **VPC** with public subnets (ALB + Fargate with public IPs) and private subnets (**ElastiCache Redis**).
- **DynamoDB** table `cw3-ElectionRaces` (partition key `raceId`, GSI `state-index` on `state`).
- **ECR** repository for the Spring Boot image.
- **ECS Fargate** service behind an **Application Load Balancer** (HTTP on port 80 by default, or HTTPS on 443 if `certificate_arn` is set).
- **S3** + **CloudFront** for the Vite production build.

Application code changes (already in the repo) support AWS:

- `AWS_USE_LOCALSTACK=false`, **IAM task role** for DynamoDB (no static `test` credentials).
- **`CORS_ALLOWED_ORIGINS`** should include your CloudFront URL `https://<distribution>.cloudfront.net` (Terraform sets this automatically unless you override `cors_allowed_origins`).
- **`DYNAMODB_TABLE_NAME`** matches the Terraform table name.

## Prerequisites

- AWS CLI and Terraform `>= 1.5` installed.
- An AWS account; **billing alarms** recommended.
- **GitHub OIDC** IAM role allowing `ecr:*` push, `ecs:UpdateService`, `s3:PutObject`, `cloudfront:CreateInvalidation` (see below).

## 1. Configure variables

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
# Edit: frontend_bucket_name (globally unique), backend_image (after first ECR push — see step 3)
```

## 2. First-time Terraform apply (infrastructure only)

```bash
terraform init
terraform apply
```

On the first run, **ECS tasks may fail** until a real image exists in ECR — that is expected.

## 3. Push the backend image

From the repo root (after `terraform apply`):

```bash
AWS_REGION=us-east-1
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGISTRY="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY"

docker build -t "$REGISTRY/cw3-backend:latest" ./backend
docker push "$REGISTRY/cw3-backend:latest"
```

Update `terraform.tfvars`:

```hcl
backend_image = "<ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/cw3-backend:latest"
```

Then:

```bash
terraform apply
```

This refreshes the ECS task definition with the image and correct **CORS** / **DynamoDB** env vars.

## 4. Secrets Manager (`cw3-secrets`) and ECS

Create **one** secret in **AWS Secrets Manager** named `cw3-secrets` (or set `secrets_manager_secret_name` in `terraform.tfvars`). Store **plain JSON** whose keys match what the app expects — Terraform injects each key as an environment variable on the container.

Required JSON keys (see [`secrets.tf`](terraform/secrets.tf) and [`.env`](../.env) / [`application.yml`](../backend/src/main/resources/application.yml)):

```json
{
  "GUARDIAN_API_KEY": "...",
  "OPEN_FEC_API_KEY": "..."
}
```

Terraform grants the **ECS task execution role** `secretsmanager:GetSecretValue` on that secret and adds the corresponding `secrets` entries to the task definition. After `terraform apply`, ECS starts a new deployment; no manual console edits are required unless you add or rename keys (then update `local.cw3_secret_env_keys` in `secrets.tf`).

If the secret uses a **customer-managed KMS key**, add `kms:Decrypt` on that key to the execution role policy (not included by default).

**Console-only alternative:** skip Terraform secrets wiring and add **Secrets** manually on a new task definition revision (same `valueFrom` pattern: `arn:...:KEY::`).

## 5. Build and upload the frontend

Point the UI at the public **HTTPS** API URL. For **CloudFront (HTTPS SPA)**, set **`api_public_base_url`** in `terraform.tfvars` to your ACM hostname (e.g. `https://api.example.com`) so `terraform output -raw api_base_url_https` matches what browsers and `VITE_API_BASE_URL` must use. If unset, the output falls back to **`https://<alb-dns>`**. **`http://` from an HTTPS page is blocked** (mixed content).

```bash
cd infra/terraform
API_BASE="$(terraform output -raw api_base_url_https)"
BUCKET="$(terraform output -raw frontend_bucket_id)"
DIST_ID="$(terraform output -raw cloudfront_distribution_id)"

cd ../../state-of-the-race
export VITE_API_BASE_URL="$API_BASE"
npm ci && npm run build

aws s3 sync dist "s3://$BUCKET" --delete
aws cloudfront create-invalidation --distribution-id "$DIST_ID" --paths "/*"
```

If the ALB is still HTTP-only (`certificate_arn` empty), `api_base_url_https` prints `(configure certificate_arn)` — add an **ACM certificate in the same region as the ALB**, set `certificate_arn`, and re-apply before using the snippet above.

### Custom domain for the SPA (e.g. `www.midtermelectiontracker.com`)

CloudFront alternate domains need an **ACM certificate in `us-east-1` (N. Virginia)** — not the same region as an `eu-north-1` ALB. Request the cert for your hostname, validate DNS (e.g. Cloudflare), set `frontend_domain_name` and `cloudfront_acm_certificate_arn` in `terraform.tfvars`, run `terraform apply`, then add a **CNAME** in Cloudflare: **`www` →** `terraform output -raw cloudfront_domain_name` (grey cloud / DNS-only). Terraform updates **CORS** on the API to `https://<frontend_domain_name>` unless `cors_allowed_origins` is overridden.

## 6. GitHub Actions OIDC

Create an IAM role with a **trust policy** like:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:YOUR_ORG/YOUR_REPO:ref:refs/heads/main"
        }
      }
    }
  ]
}
```

Attach policies allowing ECR push, ECS `UpdateService`, S3 sync to the frontend bucket, and CloudFront invalidation.

Repository secrets (see [`.github/workflows/deploy-aws.yml`](../.github/workflows/deploy-aws.yml)):

| Secret | Purpose |
|--------|---------|
| `AWS_ROLE_TO_ASSUME` | IAM role ARN for OIDC |
| `VITE_API_BASE_URL` | Public API base URL (use `https://` when the SPA is on CloudFront) |
| `S3_FRONTEND_BUCKET` | S3 bucket name |
| `CLOUDFRONT_DIST_ID` | Distribution ID |
| `ECR_REPOSITORY` | Optional; default `cw3-backend` (use `{project_name}-backend` from Terraform) |
| `ECS_CLUSTER_NAME` / `ECS_SERVICE_NAME` | Optional; default `cw3-cluster` / `cw3-backend` |
| (variable) `AWS_REGION` | Repository **variable** — must match `aws_region` in Terraform (workflow defaults to `us-east-1` if unset) |

## Remote state (recommended for teams)

Add an S3 backend and DynamoDB lock table to `versions.tf` — not included by default.

## Cost awareness

ElastiCache, ALB, Fargate, and data transfer can exceed free tier. Use **AWS Budgets** and review **PriceClass_100** on CloudFront (already set).
