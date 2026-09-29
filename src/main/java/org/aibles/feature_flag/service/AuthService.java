package org.aibles.feature_flag.service;

import org.aibles.feature_flag.dto.request.LoginRequest;
import org.aibles.feature_flag.dto.request.LogoutRequest;
import org.aibles.feature_flag.dto.request.RefreshRequest;
import org.aibles.feature_flag.dto.request.RegisterRequest;
import org.aibles.feature_flag.dto.response.AuthResponse;

public interface AuthService {
  void register(RegisterRequest request);

  AuthResponse login(LoginRequest request);

  AuthResponse refresh(RefreshRequest request);

  void logout(LogoutRequest request);
}
