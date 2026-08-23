package org.aibles.feature_flag.domain.enums;

/**
 * Authorizable operations. Roles (built-in and custom) are defined as sets of these, so this is the
 * single vocabulary the PDP checks against.
 */
public enum Action {
  FLAG_READ,
  FLAG_CREATE,
  FLAG_UPDATE,
  FLAG_DELETE,
  FLAG_ARCHIVE,
  FLAG_STATE_UPDATE,
  FLAG_STATE_UPDATE_PRODUCTION,
  ENV_READ,
  ENV_CREATE,
  ENV_UPDATE,
  ENV_DELETE,
  ENV_ROTATE_KEY,
  ENV_MANAGE_PROTECTION,
  PROJECT_READ,
  PROJECT_CREATE,
  PROJECT_UPDATE,
  PROJECT_DELETE,
  ORG_UPDATE,
  ORG_DELETE,
  MEMBER_INVITE,
  MEMBER_MANAGE,
  GRANT_MANAGE,
  ROLE_MANAGE,
  AUDIT_READ
}
