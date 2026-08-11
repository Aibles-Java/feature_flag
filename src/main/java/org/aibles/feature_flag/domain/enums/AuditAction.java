package org.aibles.feature_flag.domain.enums;

/** The kind of mutation an {@code audit_log} row records (issue #31). */
public enum AuditAction {
  CREATE,
  UPDATE,
  DELETE,
  ARCHIVE,
  UNARCHIVE,
  INVITE_MEMBER,
  REMOVE_MEMBER,
  ROTATE_API_KEY,
  CHANGE_STATE
}
