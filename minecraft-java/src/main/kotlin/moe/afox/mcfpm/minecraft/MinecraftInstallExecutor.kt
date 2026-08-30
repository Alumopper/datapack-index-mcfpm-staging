package moe.afox.mcfpm.minecraft

import moe.afox.mcfpm.core.ClientInstallRequest
import moe.afox.mcfpm.core.ClientInstallResult
import moe.afox.mcfpm.core.InstallExecutor
import moe.afox.mcfpm.model.McfpmResult

public class MinecraftInstallExecutor(
    private val engine: MinecraftInstallEngine = MinecraftInstallEngine(),
) : InstallExecutor {
    override fun install(request: ClientInstallRequest): McfpmResult<ClientInstallResult> {
        val result = engine.install(
            MinecraftInstallRequest(
                fetched = request.fetched,
                context = request.context,
                explicitWorld = request.explicitWorld,
                explicitInstance = request.explicitInstance,
                confirmedGlobalResourcePackImpact = request.confirmed,
                dryRun = request.dryRun,
                force = request.force,
            ),
        )
        return when (result) {
            is McfpmResult.Failure -> result
            is McfpmResult.Success -> {
                val installed = result.value
                McfpmResult.Success(
                    ClientInstallResult(
                        context = installed.plan.context,
                        world = installed.plan.world,
                        instance = installed.plan.instance,
                        copiedTargets = installed.plan.copies.map { it.target },
                        removedTargets = installed.plan.removals,
                        orderFiles = installed.plan.orderChanges.map { it.file },
                        evidence = installed.plan.evidence,
                        transactionId = installed.transactionId,
                        dryRun = installed.dryRun,
                    ),
                )
            }
        }
    }
}
