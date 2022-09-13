package com.bestv.remote.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author taojiacheng
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class TraceLogContext {

    private String sn = "";

    private String userId = "";

    private String extra = "";

}
