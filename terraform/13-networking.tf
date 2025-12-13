##############################
# VPC & Subnets
##############################

data "aws_vpc" "default" {
  default = true
}

resource "aws_vpc" "custom" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "proj04-custom"
  }
}

moved {
  from = aws_subnet.allowed
  to   = aws_subnet.private1
}

resource "aws_subnet" "private1" {
  vpc_id            = aws_vpc.custom.id
  cidr_block        = "10.0.0.0/24"
  availability_zone = "${var.aws_region}a"

  tags = {
    Name   = "subnet-custom-vpc-private1"
    Access = "private"
  }
}

resource "aws_subnet" "private2" {
  vpc_id            = aws_vpc.custom.id
  cidr_block        = "10.0.1.0/24"
  availability_zone = "${var.aws_region}b"

  tags = {
    Name   = "subnet-custom-vpc-private2"
    Access = "private"
  }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.custom.id
  cidr_block        = "10.0.2.0/24"
  availability_zone = "${var.aws_region}a"

  tags = {
    Name   = "subnet-custom-vpc-public1"
    Access = "public"
  }
}

resource "aws_subnet" "public2" {
  vpc_id            = aws_vpc.custom.id
  cidr_block        = "10.0.3.0/24"
  availability_zone = "${var.aws_region}b"

  tags = {
    Name   = "subnet-custom-vpc-public2"
    Access = "public"
  }
}

# For documentation. Not actively used.
resource "aws_subnet" "not_allowed" {
  vpc_id     = data.aws_vpc.default.id
  cidr_block = "172.31.128.0/24"

  tags = {
    Name = "subnet-default-vpc"
  }
}

##############################
# Internet Gateway (Required for Public RDS)
##############################

resource "aws_internet_gateway" "custom" {
  vpc_id = aws_vpc.custom.id

  tags = {
    Name = "proj04-igw"
  }
}

# Route table for public subnets
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.custom.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.custom.id
  }

  tags = {
    Name = "proj04-public-rt"
  }
}

# Associate public subnets with public route table
resource "aws_route_table_association" "public1" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public2" {
  subnet_id      = aws_subnet.public2.id
  route_table_id = aws_route_table.public.id
}

##############################
# Security Groups
##############################

# 1. Source security group - From where traffic is allowed
# 2. Compliant security group
#   2.1 Security group rule
# 3. Non-compliant security group
#   3.1 Security group rule

resource "aws_security_group" "source" {
  name        = "source-sg"
  description = "SG from where connections are allowed into the DB"
  vpc_id      = aws_vpc.custom.id
}

resource "aws_security_group" "compliant" {
  name        = "compliant-sg"
  description = "Compliant security group"
  vpc_id      = aws_vpc.custom.id

  tags = {
    Access = "private"
  }
}

# MySQL access from anywhere (for RDS public access)
resource "aws_vpc_security_group_ingress_rule" "mysql" {
  security_group_id = aws_security_group.compliant.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 3306
  to_port           = 3306
  ip_protocol       = "tcp"
  description       = "Allow MySQL access from anywhere"
}

# All traffic from your IP (for full access)
resource "aws_vpc_security_group_ingress_rule" "all_traffic_my_ip" {
  security_group_id = aws_security_group.compliant.id
  cidr_ipv4         = "104.28.205.72/32"  # Your current IP - update if it changes
  from_port         = -1
  to_port           = -1
  ip_protocol       = "-1"
  description       = "Allow all traffic from My IP"
}

resource "aws_security_group" "non_compliant" {
  name        = "non-compliant-sg"
  description = "Non-compliant security group"
  vpc_id      = aws_vpc.custom.id
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.non_compliant.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}