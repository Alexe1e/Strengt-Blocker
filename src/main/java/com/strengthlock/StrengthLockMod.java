package com.strengthlock;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /strengthlock block <1|2>   - block Strength I or II
 * /strengthlock allow <1|2>   - allow it again
 * /strengthlock status        - show what is blocked
 * Blocked levels are removed from players. Setting is saved in config/strengthlock.properties.
 */
public class StrengthLockMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("strengthlock");

	private static volatile boolean blockLevel1 = false;
	private static volatile boolean blockLevel2 = false;

	private static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("strengthlock.properties");
	}

	private static void load() {
		Path file = configFile();
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			Properties props = new Properties();
			props.load(reader);
			blockLevel1 = Boolean.parseBoolean(props.getProperty("block1", "false"));
			blockLevel2 = Boolean.parseBoolean(props.getProperty("block2", "false"));
		} catch (IOException e) {
			LOGGER.warn("Could not read config", e);
		}
	}

	private static void save() {
		Properties props = new Properties();
		props.setProperty("block1", Boolean.toString(blockLevel1));
		props.setProperty("block2", Boolean.toString(blockLevel2));
		try (Writer writer = Files.newBufferedWriter(configFile())) {
			props.store(writer, "Strength Lock: true = this Strength level is blocked");
		} catch (IOException e) {
			LOGGER.warn("Could not save config", e);
		}
	}

	private static String status() {
		return "Strength I: " + (blockLevel1 ? "GESPERRT" : "erlaubt")
				+ " | Strength II: " + (blockLevel2 ? "GESPERRT" : "erlaubt");
	}

	private static int set(CommandSourceStack source, int level, boolean block) {
		if (level == 1) {
			blockLevel1 = block;
		} else {
			blockLevel2 = block;
		}
		save();
		source.sendSystemMessage(Component.literal(status()));
		return 1;
	}

	@Override
	public void onInitialize() {
		load();

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			dispatcher.register(Commands.literal("strengthlock")
					.requires(PermissionPredicates.require(
							Identifier.fromNamespaceAndPath("strengthlock", "command"), PermissionLevel.ADMINS))
					.then(Commands.literal("block")
							.then(Commands.argument("level", IntegerArgumentType.integer(1, 2))
									.executes(ctx -> set(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "level"), true))))
					.then(Commands.literal("allow")
							.then(Commands.argument("level", IntegerArgumentType.integer(1, 2))
									.executes(ctx -> set(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "level"), false))))
					.then(Commands.literal("status")
							.executes(ctx -> {
								ctx.getSource().sendSystemMessage(Component.literal(status()));
								return 1;
							})));
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 5 != 0 || (!blockLevel1 && !blockLevel2)) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				MobEffectInstance effect = player.getEffect(MobEffects.STRENGTH);
				if (effect == null) {
					continue;
				}
				int amplifier = effect.getAmplifier();
				if ((amplifier == 0 && blockLevel1) || (amplifier == 1 && blockLevel2)) {
					player.removeEffect(MobEffects.STRENGTH);
					player.sendSystemMessage(Component.literal("Strength " + (amplifier + 1) + " ist auf diesem Server gesperrt."));
				}
			}
		});
	}
}
