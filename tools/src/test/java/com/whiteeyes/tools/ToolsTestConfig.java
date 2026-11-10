package com.whiteeyes.tools;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.whiteeyes.common.testing.TestRegionLoader;

/** tools는 com.whiteeyes.tools만 읽으므로 시험 도우미를 직접 등록한다. */
@Configuration
@Import(TestRegionLoader.class)
class ToolsTestConfig {}
