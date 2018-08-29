package com.accolite.pru.health.AuthApp.service;

import com.accolite.pru.health.AuthApp.exception.ResourceAlreadyInUseException;
import com.accolite.pru.health.AuthApp.exception.ResourceNotFoundException;
import com.accolite.pru.health.AuthApp.exception.TokenRefreshException;
import com.accolite.pru.health.AuthApp.exception.UpdatePasswordException;
import com.accolite.pru.health.AuthApp.model.CustomUserDetails;
import com.accolite.pru.health.AuthApp.model.TokenStatus;
import com.accolite.pru.health.AuthApp.model.User;
import com.accolite.pru.health.AuthApp.model.UserDevice;
import com.accolite.pru.health.AuthApp.model.payload.LoginRequest;
import com.accolite.pru.health.AuthApp.model.payload.RegistrationRequest;
import com.accolite.pru.health.AuthApp.model.payload.TokenRefreshRequest;
import com.accolite.pru.health.AuthApp.model.payload.UpdatePasswordRequest;
import com.accolite.pru.health.AuthApp.model.token.EmailVerificationToken;
import com.accolite.pru.health.AuthApp.model.token.RefreshToken;
import com.accolite.pru.health.AuthApp.security.JwtTokenProvider;
import com.accolite.pru.health.AuthApp.util.Util;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

	@Autowired
	private UserService userService;

	@Autowired
	private RoleService roleService;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@Autowired
	private RefreshTokenService refreshTokenService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private AuthenticationManager authenticationManager;

	@Autowired
	private EmailVerificationTokenService emailVerificationTokenService;

	@Autowired
	private UserDeviceService userDeviceService;

	private static final Logger logger = Logger.getLogger(AuthService.class);

	/**
	 * Registers a new user in the database by performing a series of quick checks.
	 * @return A user object if successfully created
	 */
	public Optional<User> registerUser(RegistrationRequest newRegistrationRequest) {
		String newRegistrationRequestEmail = newRegistrationRequest.getEmail();
		if (emailAlreadyExists(newRegistrationRequestEmail)) {
			logger.error("Email already exists: " + newRegistrationRequestEmail);
			throw new ResourceAlreadyInUseException("Email", "Address", newRegistrationRequestEmail);
		}
		logger.info("Trying to register new user [" + newRegistrationRequestEmail + "]");
		User newUser = userService.createUser(newRegistrationRequest);
		User registeredNewUser = userService.save(newUser);
		return Optional.ofNullable(registeredNewUser);
	}

	/**
	 * Checks if the given email already exists in the database repository or not
	 * @return true if the email exists else false
	 */
	public Boolean emailAlreadyExists(String email) {
		return userService.existsByEmail(email);
	}

	/**
	 * Checks if the given email already exists in the database repository or not
	 * @return true if the email exists else false
	 */
	public Boolean usernameAlreadyExists(String username) {
		return userService.existsByUsername(username);
	}

	/**
	 * Authenticate user and log them in given a loginRequest
	 */
	public Optional<Authentication> authenticateUser(LoginRequest loginRequest) {
		return Optional.ofNullable(authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(loginRequest.getEmail(),
				loginRequest.getPassword())));
	}

	/**
	 * Confirms the user verification based on the token expiry and mark the user as active.
	 * If user is already registered, save the unnecessary database calls.
	 */
	public Optional<User> confirmEmailRegistration(String emailToken) {
		Optional<EmailVerificationToken> emailVerificationTokenOpt =
				emailVerificationTokenService.findByToken(emailToken);
		emailVerificationTokenOpt.orElseThrow(() ->
				new ResourceNotFoundException("Token", "Email verification", emailToken));

		Optional<User> registeredUser = emailVerificationTokenOpt.map(EmailVerificationToken::getUser);
		Boolean userAlreadyVerified =
				emailVerificationTokenOpt.map(EmailVerificationToken::getUser)
						.map(User::getEmailVerified).filter(Util::isTrue).orElse(false);

		if (userAlreadyVerified) {
			logger.info("User [" + emailToken + "] already registered.");
			return registeredUser;
		}
		emailVerificationTokenOpt.ifPresent(emailVerificationTokenService::verifyExpiration);

		emailVerificationTokenOpt.ifPresent(token -> {
			token.setTokenStatus(TokenStatus.STATUS_CONFIRMED);
			emailVerificationTokenService.save(token);
			User user = registeredUser.get();
			user.setEmailVerified(true);
			userService.save(user);
		});
		return registeredUser;
	}

	/**
	 * Attempt to regenerate a new email verification token given a valid
	 * previous expired token. If the previous token is valid, increase its expiry
	 * else update the token value and add a new expiration.
	 */
	public Optional<EmailVerificationToken> recreateRegistrationToken(String existingToken) {
		Optional<EmailVerificationToken> emailVerificationTokenOpt =
				emailVerificationTokenService.findByToken(existingToken);
		emailVerificationTokenOpt.orElseThrow(() ->
				new ResourceNotFoundException("Token", "Existing email verification", existingToken));
		Boolean userAlreadyVerified =
				emailVerificationTokenOpt.map(EmailVerificationToken::getUser)
						.map(User::getEmailVerified).filter(Util::isTrue).orElse(false);
		if (userAlreadyVerified) {
			return Optional.empty();
		}
		return emailVerificationTokenOpt.map(emailVerificationTokenService::updateExistingTokenWithNameAndExpiry);
	}

	/**
	 * Validates the password of the current logged in user with the given password
	 */
	public Boolean currentPasswordMatches(User currentUser, String password) {
		return passwordEncoder.matches(password, currentUser.getPassword());
	}

	/**
	 * Updates the password of the current logged in user
	 */
	public Optional<User> updatePassword(CustomUserDetails customUserDetails,
			UpdatePasswordRequest updatePasswordRequest) {
		User currentUser = userService.getLoggedInUser(customUserDetails.getEmail());

		if (!currentPasswordMatches(currentUser, updatePasswordRequest.getOldPassword())) {
			logger.info("Current password is invalid for [" + currentUser.getPassword() + "]");
			throw new UpdatePasswordException(currentUser.getEmail(), "Invalid current password");
		}
		String newPassword = passwordEncoder.encode(updatePasswordRequest.getNewPassword());
		currentUser.setPassword(newPassword);
		userService.save(currentUser);
		return Optional.ofNullable(currentUser);
	}

	/**
	 * Generates a JWT token for the validated client
	 */
	public String generateToken(Authentication authentication) {
		return tokenProvider.generateToken(authentication);
	}

	/**
	 * Creates and persists the refresh token for the user device. If device exists
	 * already, we don't care. Unused devices with expired tokens should be cleaned
	 * with a cron job. The generated token would be encapsulated within the jwt.
	 */
	public Optional<RefreshToken> createAndPersistRefreshTokenForDevice(Authentication authentication,
			LoginRequest loginRequest) {
		User currentUser = (User) authentication.getPrincipal();
		RefreshToken refreshToken = refreshTokenService.createRefreshToken();
		UserDevice userDevice = userDeviceService.createUserDevice(loginRequest.getDeviceInfo());
		userDevice.setUser(currentUser);
		userDevice.setRefreshToken(refreshToken);
		refreshToken.setUserDevice(userDevice);
		userDeviceService.save(userDevice);
		refreshTokenService.save(refreshToken);
		return Optional.ofNullable(refreshToken);
	}

	/**
	 * Refresh the expired jwt token using a refresh token and device info. The
	 * * refresh token is mapped to a specific device and if it is unexpired, can help
	 * * generate a new jwt. If the refresh token is inactive for a device or it is expired,
	 * * throw appropriate errors.
	 */
	public Optional<String> refreshJwtToken(TokenRefreshRequest tokenRefreshRequest) {
		//tokenFromDb's device info should match this one
		String requestRefreshToken = tokenRefreshRequest.getRefreshToken();
		Optional<RefreshToken> refreshTokenOpt =
				refreshTokenService.findByToken(requestRefreshToken);
		refreshTokenOpt.orElseThrow(() -> new TokenRefreshException(requestRefreshToken, "Missing refresh token in " +
				"database. Please login again"));

		//token should not be expired else error.
		//user device shouldn't be blocked for refresh
		//if all good, generate a new jwt, update the count of usage
//		refreshTokenOpt.get().getExpiryDate()

		return null;
	}
}
