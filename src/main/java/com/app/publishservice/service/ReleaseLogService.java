package com.app.publishservice.service;

import com.app.publishservice.domain.entity.AppReleaseRecord;
import com.app.publishservice.domain.entity.AppReleaseTaskLog;
import com.app.publishservice.domain.enums.ReleaseStatus;
import com.app.publishservice.repository.AppReleaseTaskLogRepository;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReleaseLogService {

    private final AppReleaseTaskLogRepository logRepository;

    public ReleaseLogService(AppReleaseTaskLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    public void log(AppReleaseRecord record, String action, ReleaseStatus before, ReleaseStatus after, String message, String payload) {
        AppReleaseTaskLog log = new AppReleaseTaskLog();
        log.setReleaseRecordId(record.getId());
        log.setReleaseRecord(record);
        log.setAction(action);
        log.setStatusBefore(before == null ? null : before.getCode());
        log.setStatusAfter(after == null ? null : after.getCode());
        log.setMessage(message);
        log.setPayload(payload);
        logRepository.insert(log);
    }

    public List<AppReleaseTaskLog> listByReleaseRecordId(Long releaseRecordId) {
        return logRepository.selectList(
                Wrappers.<AppReleaseTaskLog>lambdaQuery()
                        .eq(AppReleaseTaskLog::getReleaseRecordId, releaseRecordId)
                        .orderByAsc(AppReleaseTaskLog::getCreateTime)
                        .orderByAsc(AppReleaseTaskLog::getId)
        );
    }
}
