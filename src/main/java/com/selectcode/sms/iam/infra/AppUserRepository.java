package com.selectcode.sms.iam.infra;

import com.selectcode.sms.iam.domain.AppUser;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Tenant-scoped: every query is limited to the current school by Hibernate and by row-level security. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	@Query("select u from AppUser u where lower(u.username) = lower(?1)")
	Optional<AppUser> findByUsernameIgnoreCase(String username);

	@Query("""
			select u from AppUser u
			where (:q = '' or lower(u.username) like concat('%', :q, '%')
			       or lower(u.displayName) like concat('%', :q, '%')
			       or lower(coalesce(u.email, '')) like concat('%', :q, '%'))
			  and u.status in :statuses
			order by u.displayName""")
	Page<AppUser> search(String q, java.util.Collection<AppUser.Status> statuses, Pageable pageable);

	Optional<AppUser> findByMainAdminTrue();

	Optional<AppUser> findByStaffId(long staffId);

	long countByStatus(AppUser.Status status);

	/*
	 * Sign-in bookkeeping is written with single atomic statements, not through the entity: it must not bump the
	 * optimistic-lock version (an admin editing the user would get a false conflict) and two sign-ins of the same user
	 * at the same moment must both succeed.
	 */

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			update app_user set failed_attempts = 0, locked_until = null, last_login_at = :now
			where id = :id""", nativeQuery = true)
	void recordSuccessfulSignIn(long id, java.time.Instant now);

	/** Counts a wrong password; after {@code max} in a row the account is locked until {@code lockUntil}. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			update app_user
			set locked_until = case when failed_attempts + 1 >= :max then :lockUntil else locked_until end,
			    failed_attempts = case when failed_attempts + 1 >= :max then 0 else failed_attempts + 1 end
			where id = :id""", nativeQuery = true)
	void recordFailedSignIn(long id, int max, java.time.Instant lockUntil);
}
