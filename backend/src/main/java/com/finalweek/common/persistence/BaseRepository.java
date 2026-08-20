package com.finalweek.common.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.reflection.SystemMetaObject;

/** Persistence facade that keeps MyBatis-Plus details out of application services. */
public interface BaseRepository<T> extends BaseMapper<T>, RepositoryMarker {
    default Optional<T> findById(Serializable id) {
        return Optional.ofNullable(selectById(id));
    }

    default T save(T entity) {
        var table = TableInfoHelper.getTableInfo(entity.getClass());
        if (table == null || table.getKeyProperty() == null) {
            throw new IllegalStateException("Missing MyBatis-Plus table metadata for " + entity.getClass().getName());
        }
        var metaObject = SystemMetaObject.forObject(entity);
        var id = metaObject.getValue(table.getKeyProperty());
        var inserting = id == null || selectById((Serializable) id) == null;
        LifecycleCallbacks.invoke(entity, inserting ? BeforeInsert.class : BeforeUpdate.class);
        if (inserting && id == null && table.getKeyType() == UUID.class) {
            metaObject.setValue(table.getKeyProperty(), UUID.randomUUID());
        }
        if (inserting) insert(entity); else updateById(entity);
        return entity;
    }

    default T saveAndFlush(T entity) {
        return save(entity);
    }

    default <S extends T> List<S> saveAll(Iterable<S> entities) {
        var saved = new ArrayList<S>();
        entities.forEach(value -> {
            save(value);
            saved.add(value);
        });
        return saved;
    }

    default void delete(T entity) {
        deleteById(entity);
    }

    default void flush() {
        // MyBatis executes statements immediately and has no ORM persistence context to flush.
    }
}
