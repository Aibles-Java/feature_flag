package org.aibles.feature_flag.domain.enums;

/** The kind of domain entity an {@code audit_log} row refers to (issue #31). */
public enum AuditEntityType {
  ORGANIZATION,
  PROJECT,
  ENVIRONMENT,
  FEATURE_FLAG,
  FLAG_STATE,
  MEMBER,
  API_KEY,
  PERMISSION_GRANT,
  CUSTOM_ROLE
}
