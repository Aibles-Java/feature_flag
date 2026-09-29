package org.aibles.feature_flag.domain.enums;

public enum MemberRole {
  OWNER,
  ADMIN,
  VIEWER,
  /**
   * In the organisation, with no reach into any project of its own.
   *
   * <p>Where VIEWER carries org-wide read, MEMBER carries only what someone needs to know the
   * organisation exists: read it, and see who else is in it. Everything about projects,
   * environments and flags has to arrive as a project grant. This is the role to use for someone
   * who should work on one project and not see the rest.
   */
  MEMBER
}
