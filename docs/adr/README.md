# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for feature_flag.

## What is an ADR?

An ADR documents a significant architectural decision: the context, the options
considered, and the rationale behind the choice.

## How to create a new ADR

1. Ask Claude: "create a new ADR for [topic]"
2. Name it `ADR-XXXX-short-title.md` (increment the number)
3. Fill in all sections — do not leave placeholders
4. Update the index table below

## Index

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [ADR-0001](ADR-0001-initial-architecture.md) | Initial Architecture | Accepted | 2026-07-01 |
| [ADR-0002](ADR-0002-release-process.md) | Release Process (develop → release → main) | Accepted | 2026-07-01 |
| [ADR-0003](ADR-0003-pagination-strategy.md) | Pagination Strategy for Admin List Endpoints | Accepted | 2026-07-15 |
| [ADR-0004](ADR-0004-percentage-rollout-contract.md) | Identifier-Based Percentage Rollout — Evaluation Contract | Accepted | 2026-08-05 |
| [ADR-0005](ADR-0005-webhook-delivery-and-secret-storage.md) | Webhook Delivery, Signing, and Secret Storage | Accepted | 2026-08-06 |
