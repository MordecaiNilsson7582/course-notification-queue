package dev.learning;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CourseNotificationsTest {
    @Test void routesEachLessonToItsLearnerAndRejectsUnknownEvents() {
        var ready = new CourseNotifications.LessonEvent("lesson-42", "teacher-8", "student-7", "video-3", "asset_ready");
        assertEquals("learner-student-7", CourseNotifications.channel(ready));
        var processed = new CourseNotifications.LessonEvent("lesson-43", "teacher-8", "student-9", "video-3", "content_processed");
        assertEquals("learner-student-9", CourseNotifications.channel(processed));
        assertThrows(IllegalArgumentException.class, () -> CourseNotifications.channel(
                new CourseNotifications.LessonEvent("lesson-44", "teacher-8", "student-7", "video-3", "unknown")));
    }
}
