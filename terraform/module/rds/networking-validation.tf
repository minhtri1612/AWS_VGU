##############################
# Subnet Validation
##############################

data "aws_vpc" "default" {
  default = true
}

data "aws_subnet" "input" {
  for_each = var.subnet_map
  id       = each.value

  lifecycle {
    postcondition {
      condition     = self.vpc_id != data.aws_vpc.default.id
      error_message = <<-EOT
        The following subnet is part of the default VPC:

        Name = ${try(self.tags.Name, "N/A")}
        ID   = ${self.id}

        Please do not deploy RDS instances in the default VPC.
        EOT
    }

    postcondition {
      condition     = can(lower(self.tags.Access)) && contains(["private", "public"], lower(self.tags.Access))
      error_message = <<-EOT
        The following subnet is not properly tagged:

        Name = ${try(self.tags.Name, "N/A")}
        ID   = ${self.id}

        Please ensure that the subnet is properly tagged by adding the following tags:
        1. Access = "private" (for private RDS) OR Access = "public" (for public RDS)
        EOT
    }
  }
}

##############################
# Security Group Validation
##############################

data "aws_security_group" "input" {
  count = length(var.security_group_ids)
  id    = var.security_group_ids[count.index]

  lifecycle {
    postcondition {
      condition = can(self.tags.Access) && lower(self.tags.Access) == "private"
      error_message = <<-EOT
      The following security group is not marked as private:

        Name = ${try(self.tags.Name, "N/A")}
        ID   = ${self.id}

      Please ensure that the security group is properly tagged by adding the following tags:
      1. Access = "private"
      EOT
    }
  }
}
