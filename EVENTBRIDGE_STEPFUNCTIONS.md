# EventBridge + Step Functions Integration Guide

## ✅ YES! You CAN use EventBridge and Step Functions together!

This document explains the **3 main patterns** for using EventBridge and Step Functions together in your project.

---

## Pattern 1: EventBridge Triggers Step Functions (S3 → EventBridge → Step Functions)

**What it does:**
- When a file is uploaded to S3, EventBridge detects it
- EventBridge automatically starts a Step Functions execution
- Step Functions runs your 3-activity workflow

**Use case:** Automatic processing when files are uploaded

**Flow:**
```
S3 Upload → EventBridge Rule → Step Functions → 3 Activities (Parallel)
```

**Configuration:** See `terraform/22-step_functions.tf` - `aws_cloudwatch_event_rule.s3_upload_trigger`

---

## Pattern 2: EventBridge Schedules Step Functions (Warm-up)

**What it does:**
- EventBridge runs Step Functions every 5 minutes
- Keeps the workflow "warm" to reduce cold starts
- Uses a test payload

**Use case:** Performance optimization - reduce latency

**Flow:**
```
EventBridge Schedule (every 5 min) → Step Functions → Warm-up execution
```

**Configuration:** See `terraform/22-step_functions.tf` - `aws_cloudwatch_event_rule.warmup_step_functions`

---

## Pattern 3: Step Functions Sends Events to EventBridge

**What it does:**
- Step Functions completes the workflow
- Sends a completion event to EventBridge
- Other services can react to this event

**Use case:** Notifications, logging, triggering other workflows

**Flow:**
```
Step Functions → Completion Event → EventBridge → Other Services
```

**Configuration:** See `terraform/22-step_functions.tf` - `SendCompletionEvent` state in Step Functions definition

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    EVENTBRIDGE (Event Bus)                   │
│                                                               │
│  ┌─────────────────┐    ┌─────────────────┐                 │
│  │ S3 Upload Rule │    │ Schedule Rule   │                 │
│  │ (Pattern 1)    │    │ (Pattern 2)     │                 │
│  └────────┬────────┘    └────────┬────────┘                 │
│           │                      │                            │
│           └──────────┬───────────┘                            │
│                      │                                        │
│                      ▼                                        │
│           ┌──────────────────────┐                           │
│           │  STEP FUNCTIONS      │                           │
│           │  Upload Workflow     │                           │
│           │                      │                           │
│           │  ┌────────────────┐  │                           │
│           │  │ Parallel Exec  │  │                           │
│           │  │ ├─ Activity 1  │  │                           │
│           │  │ ├─ Activity 2  │  │                           │
│           │  │ └─ Activity 3  │  │                           │
│           │  └────────┬───────┘  │                           │
│           │           │          │                           │
│           └───────────┼──────────┘                           │
│                       │                                        │
│                       ▼                                        │
│           ┌──────────────────────┐                           │
│           │ Completion Event      │                           │
│           │ (Pattern 3)          │                           │
│           └──────────────────────┘                           │
└─────────────────────────────────────────────────────────────┘
```

---

## Benefits of Using Both Together

1. **Event-Driven Architecture**: React to events automatically
2. **Scheduling**: Run workflows on a schedule (warm-up, reports, cleanup)
3. **Decoupling**: Services don't need to know about each other
4. **Visual Workflow**: Step Functions provides visual representation
5. **Error Handling**: Built-in retries and error handling in Step Functions
6. **Observability**: Track events in EventBridge, track workflow in Step Functions

---

## Current Implementation

Your Terraform file (`22-step_functions.tf`) includes:

✅ Step Functions state machine with 3 parallel activities  
✅ EventBridge rule for S3 uploads → Step Functions  
✅ EventBridge rule for scheduled warm-up  
✅ Step Functions sends completion events to EventBridge  
✅ All necessary IAM roles and permissions  

---

## How to Use

### Option A: Keep Current Orchestrator Lambda
- Frontend → `LambdaOrchestrateUploadHandler` → 3 Activities
- **No changes needed** - current setup works!

### Option B: Use Step Functions (Recommended)
- Frontend → `LambdaStepFunctionsStarter` → Step Functions → 3 Activities
- **Better**: Visual workflow, better error handling, built-in retries

### Option C: Use EventBridge + Step Functions (Most Event-Driven)
- S3 Upload → EventBridge → Step Functions → 3 Activities
- **Best for automation**: No frontend code needed, fully automatic

---

## Next Steps

1. **Deploy Step Functions**: `terraform apply` to create the state machine
2. **Choose your pattern**: Decide which integration you want to use
3. **Update frontend** (if using Option B): Point to Step Functions starter Lambda
4. **Test**: Upload a file and watch the workflow execute!

---

## Resources

- **Step Functions Console**: View workflow executions visually
- **EventBridge Console**: See all events and rules
- **CloudWatch Logs**: Detailed execution logs

---

**Created:** 2025-12-13  
**Status:** ✅ Ready to deploy

