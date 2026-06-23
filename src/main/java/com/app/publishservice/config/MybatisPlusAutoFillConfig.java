package com.app.publishservice.config;

import com.app.publishservice.auth.CurrentUserContextHolder;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MybatisPlusAutoFillConfig implements MetaObjectHandler {

    private final CurrentUserContextHolder currentUserContextHolder;

    public MybatisPlusAutoFillConfig(CurrentUserContextHolder currentUserContextHolder) {
        this.currentUserContextHolder = currentUserContextHolder;
    }

    /**
     * 鏂板Fill銆?
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String username = currentUsername();
        strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createUser", String.class, username);
        strictInsertFill(metaObject, "updateUser", String.class, username);
    }

    /**
     * 鏇存柊Fill銆?
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        setFieldValByName("updateUser", currentUsername(), metaObject);
    }

    private String currentUsername() {
        return currentUserContextHolder.getCurrentUsername().orElse("system");
    }
}
