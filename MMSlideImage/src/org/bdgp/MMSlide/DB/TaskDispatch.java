package org.bdgp.MMSlide.DB;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable
public class TaskDispatch {
    @DatabaseField(canBeNull=false, uniqueCombo=true) private int parentTaskId;
    @DatabaseField(canBeNull=false, uniqueCombo=true) private int taskId;

    public TaskDispatch() {}
    public TaskDispatch(int taskId, int parentTaskId) {
        this.taskId = taskId;
        this.parentTaskId = parentTaskId;
    }
    public int getParentTaskId() { return this.parentTaskId; }
    public int getTaskId() { return this.taskId; }
}