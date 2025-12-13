module "database" {
  source       = "./module/rds"
  project_name = "project1"

  security_group_ids = [
    aws_security_group.compliant.id
  ]
  subnet_ids = [
    aws_subnet.public.id,
    aws_subnet.public2.id
  ]

  subnet_map = {
    public1 = aws_subnet.public.id
    public2 = aws_subnet.public2.id
  }

  credentials = {
    username = "admin"
    password = var.db_password
  }
}