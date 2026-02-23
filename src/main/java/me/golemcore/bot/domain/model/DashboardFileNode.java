package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardFileNode {
    private String path;
    private String name;
    private String type;
    private Long size;
    private List<DashboardFileNode> children;
}
