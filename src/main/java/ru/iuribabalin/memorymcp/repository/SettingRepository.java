package ru.iuribabalin.memorymcp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.iuribabalin.memorymcp.entity.Setting;

public interface SettingRepository extends JpaRepository<Setting, String> {
}
