package com.finalweek.auth;

import com.finalweek.common.persistence.BaseRepository;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserAccountRepository extends BaseRepository<UserAccount> {

    @Select("select * from user_account where email = #{email}")
    Optional<UserAccount> findByEmail(String email);

    @Select("select * from user_account where id = #{id} for update")
    Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);
}
