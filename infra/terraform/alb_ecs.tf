locals {
  # Browser Origin must match exactly (custom www domain vs default *.cloudfront.net).
  cors_effective = trimspace(var.cors_allowed_origins) != "" ? var.cors_allowed_origins : (
    trimspace(var.frontend_domain_name) != "" ? "https://${var.frontend_domain_name}" : "https://${aws_cloudfront_distribution.app.domain_name}"
  )
  redis_host = aws_elasticache_cluster.redis.cache_nodes[0].address
}

resource "aws_cloudwatch_log_group" "ecs_backend" {
  name              = "/ecs/${var.project_name}-backend"
  retention_in_days = 14
}

resource "aws_lb" "api" {
  name               = "${var.project_name}-api"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  tags = { Name = "${var.project_name}-api-alb" }
}

resource "aws_lb_target_group" "api" {
  name        = "${var.project_name}-api-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"

  health_check {
    enabled             = true
    path                = "/actuator/health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }

  deregistration_delay = 30
}

resource "aws_lb_listener" "main" {
  load_balancer_arn = aws_lb.api.arn
  port              = var.certificate_arn != "" ? 443 : 80
  protocol          = var.certificate_arn != "" ? "HTTPS" : "HTTP"
  ssl_policy        = var.certificate_arn != "" ? "ELBSecurityPolicy-TLS13-1-2-2021-06" : null
  certificate_arn   = var.certificate_arn != "" ? var.certificate_arn : null

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_ecs_cluster" "main" {
  name = "${var.project_name}-cluster"
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "${var.project_name}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name  = "backend"
      image = var.backend_image
      essential = true
      portMappings = [
        {
          containerPort = 8080
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "AWS_REGION", value = var.aws_region },
        { name = "AWS_USE_LOCALSTACK", value = "false" },
        { name = "SPRING_DATA_REDIS_HOST", value = local.redis_host },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "DYNAMODB_TABLE_NAME", value = aws_dynamodb_table.election_races.name },
        { name = "CORS_ALLOWED_ORIGINS", value = local.cors_effective }
      ]
      secrets = [
        for k in local.cw3_secret_env_keys : {
          name      = k
          valueFrom = "${data.aws_secretsmanager_secret.cw3.arn}:${k}::"
        }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs_backend.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])

}

resource "aws_ecs_service" "backend" {
  name            = "${var.project_name}-backend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "backend"
    container_port   = 8080
  }

  depends_on = [
    aws_lb_listener.main,
    aws_cloudfront_distribution.app
  ]
}
