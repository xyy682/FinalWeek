package com.finalweek.upload;

import com.finalweek.common.persistence.BaseRepository;
import com.finalweek.material.Material;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;

public interface UploadCompletionRepository extends BaseRepository<UploadCompletion> {
    @Select("select material.* from upload_completion join material on material.id = upload_completion.material_id " +
            "where upload_completion.upload_id = #{uploadId}")
    Material findMaterialByUploadId(UUID uploadId);
}
