package cn.tudijiance.breeding;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 这一季接着哪一季 */
@Data
@TableName("season_follow")
public class SeasonFollow {

    private Long seasonId;
    private Long prevSeasonId;
}
