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
  /** A new SDK key was minted for an environment (never records the key itself). */
  CREATE_API_KEY,
  /** An SDK key was withdrawn. Soft: the row survives so the audit trail keeps its referent. */
  REVOKE_API_KEY,
  CHANGE_STATE,
  GRANT_PERMISSION,
  REVOKE_PERMISSION,
  /** An environment was cloned from another one, flag states included (issue #38). */
  CLONE,
  /** A flag snapshot was applied to an environment (issue #38). */
  IMPORT
}
