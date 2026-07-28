package com.novelforge.cli;

import com.novelforge.core.project.BookProject;
import com.novelforge.core.state.TruthState;
import com.novelforge.core.models.Book;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Rollback command — list backups or rollback truth state.
 * Usage: rollback <path> list
 *        rollback <path> to <timestamp>
 *        rollback <path> last
 */
public class RollbackCommand {

    public void execute(String[] args) {
        if (args.length < 2) {
            System.err.println("用法: rollback <项目路径> list | to <timestamp> | last");
            return;
        }

        String bookPath = args[0];
        String action = args[1];

        try {
            Path path = Paths.get(bookPath);
            Book book = BookProject.loadBook(path);
            TruthState state = new TruthState(path);

            if ("list".equals(action)) {
                List<Long> versions = state.getBackupVersions();
                if (versions.isEmpty()) {
                    System.out.println("暂无备份版本");
                } else {
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    System.out.println("备份版本列表:");
                    for (Long ts : versions) {
                        System.out.println("  " + fmt.format(new Date(ts)) + "  (timestamp: " + ts + ")");
                    }
                }
            } else if ("to".equals(action)) {
                if (args.length < 3) {
                    System.err.println("用法: rollback <路径> to <timestamp>");
                    return;
                }
                long timestamp = Long.parseLong(args[2]);
                boolean success = state.rollbackTo(timestamp);
                System.out.println(success ? "✦ 回滚成功" : "✗ 回滚失败（备份不存在）");
            } else if ("last".equals(action)) {
                boolean success = state.rollback();
                System.out.println(success ? "✦ 回滚至上一版本成功" : "✗ 无上一版本可回滚");
            } else {
                System.err.println("未知操作: " + action + " (支持: list, to, last)");
            }
        } catch (Exception e) {
            System.err.println("回滚失败: " + e.getMessage());
        }
    }
}
