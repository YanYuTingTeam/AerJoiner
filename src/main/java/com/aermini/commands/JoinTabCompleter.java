package com.aermini.commands;

import com.aermini.AerJoiner;
import com.aermini.managers.CategoryData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class JoinTabCompleter implements TabCompleter {
    private final AerJoiner plugin;

    public JoinTabCompleter(AerJoiner plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("gui");
            completions.addAll(plugin.getServerManager().getCategories().stream()
                    .map(CategoryData::getName)
                    .collect(Collectors.toList()));
            return filter(completions, args[0]);
        }
        if (args.length == 2 && "gui".equalsIgnoreCase(args[0])) {
            return filter(plugin.getServerManager().getCategories().stream()
                    .map(CategoryData::getName)
                    .collect(Collectors.toList()), args[1]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }
}
