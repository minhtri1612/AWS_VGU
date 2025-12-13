variable "project_name" {
  type = string
  default = "project4_rds"
  
}

variable "instance_class" {
    type = string
    default = "db.t3.micro"

    validation {
      condition = contains(["db.t3.micro"], var.instance_class)
        error_message = "Only db.t3.micro is allowed for instance_class."
    }       
  
}

variable "storage_size" {
    type = number
    default = 20

    validation {
      condition = var.storage_size >= 20 && var.storage_size <= 1000
      error_message = "Storage size must be between 20 and 1000 GB."
    }
  
}

variable "engine" {
    type = string
    default = "mysql"

    validation {
      condition = contains(["mysql", "postgres"], var.engine)
      error_message = "Only 'mysql' and 'postgres' are allowed for engine."
    }
  
}

variable "credentials" {
  type = object({
    username = string
    password = string
  })

  sensitive   = true
  description = "The root username and password for the RDS instance creation."

  validation {
    condition = (
      length(regexall("[a-zA-Z]+", var.credentials.password)) > 0
      && length(regexall("[0-9]+", var.credentials.password)) > 0
      && length(regexall("^[a-zA-Z0-9+_?\\-.]{8,}$", var.credentials.password)) > 0
    )
    error_message = <<-EOT
    Password must comply with the following format:

    1. Contain at least 1 letter
    2. Contain at least 1 digit
    3. Be at least 8 characters long
    4. Contain only the following characters: a-z, A-Z, 0-9, +, _, ?, -, .
    EOT
  }
}

variable "subnet_ids" {
  type = list(string)
  
}


variable "security_group_ids" {
  type = list(string)
  
}

variable "subnet_map" {
  type = map(string)
  description = "Map of subnet names to subnet IDs."
}