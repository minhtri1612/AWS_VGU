locals {
  db_engine = {
    "mysql" = {
      engine  = "mysql"
      version = "8.0.43"  # Change from 8.0.36 to 8.0.43
      family  = "mysql8.0"
    }
    "postgres" = {
      engine  = "postgres"
      version = "13.7"
      family  = "postgres13"
    }
  }
}



resource "aws_db_subnet_group" "this" {
  name = "${var.project_name}-db-subnet-group"
  description = "Subnet group for RDS instances"
  subnet_ids = var.subnet_ids

  tags = {
    Name = "${var.project_name}-db-subnet-group"
  }
  
}

resource "aws_db_parameter_group" "this" {
  name        = "${var.project_name}-db-parameter-group"
  family     = local.db_engine[var.engine].family
  description = "DB parameter group for RDS instances"

  tags = {
    Name = "${var.project_name}-db-parameter-group"
  }
}

resource "aws_db_instance" "this" {
  identifier = var.project_name
  instance_class = var.instance_class
  allocated_storage = var.storage_size
  engine               = local.db_engine[var.engine].engine
  engine_version = local.db_engine[var.engine].version  # Use the local value instead of hardcoded
  db_subnet_group_name = aws_db_subnet_group.this.name
  vpc_security_group_ids = var.security_group_ids
  username = var.credentials.username
  password = var.credentials.password
  skip_final_snapshot = true
  publicly_accessible  = true  # Set to true for SQL Workbench access


}