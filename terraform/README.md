# Terraform Setup

## Quick Start

You have **two options** to provide your RDS password:

### Option 1: Edit terraform.tfvars (Already created for you!)
Just open `terraform.tfvars` and replace `"CHANGE_ME"` with your password:
```bash
nano terraform.tfvars  # or use your preferred editor
```

Then run:
```bash
terraform init
terraform plan
terraform apply
```

### Option 2: Use Environment Variable (No file editing needed!)
```bash
export TF_VAR_db_password="YourSecurePassword123"
terraform init
terraform plan
terraform apply
```

## Password Requirements
- At least 1 letter
- At least 1 digit
- At least 8 characters long
- Allowed characters: a-z, A-Z, 0-9, +, _, ?, -, .

## What's Automated
✅ VPC creation
✅ Subnet creation (automatically uses available AZs)
✅ Security groups
✅ All networking configuration
✅ RDS setup

You only need to provide the database password!

