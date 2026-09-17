package com.campusbooking.activity.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("activity_session")
public class ActivitySession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long activityId;
    private Integer capacity;
    private Integer remainingCapacity;
    private java.time.Instant registrationStartAt;
    private java.time.Instant registrationEndAt;
    private java.time.Instant startAt;
    private java.time.Instant endAt;
    private String status;
    private java.time.Instant runtimeExpireAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Integer getRemainingCapacity() { return remainingCapacity; }
    public void setRemainingCapacity(Integer remainingCapacity) { this.remainingCapacity = remainingCapacity; }
    public java.time.Instant getRegistrationStartAt() { return registrationStartAt; }
    public void setRegistrationStartAt(java.time.Instant registrationStartAt) { this.registrationStartAt = registrationStartAt; }
    public java.time.Instant getRegistrationEndAt() { return registrationEndAt; }
    public void setRegistrationEndAt(java.time.Instant registrationEndAt) { this.registrationEndAt = registrationEndAt; }
    public java.time.Instant getStartAt() { return startAt; }
    public void setStartAt(java.time.Instant startAt) { this.startAt = startAt; }
    public java.time.Instant getEndAt() { return endAt; }
    public void setEndAt(java.time.Instant endAt) { this.endAt = endAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public java.time.Instant getRuntimeExpireAt() { return runtimeExpireAt; }
    public void setRuntimeExpireAt(java.time.Instant runtimeExpireAt) { this.runtimeExpireAt = runtimeExpireAt; }
}

