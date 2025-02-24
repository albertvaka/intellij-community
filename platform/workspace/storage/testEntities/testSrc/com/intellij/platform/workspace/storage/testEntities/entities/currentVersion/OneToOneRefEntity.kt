// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.workspace.storage.testEntities.entities.currentVersion

import com.intellij.platform.workspace.storage.*
import com.intellij.platform.workspace.storage.annotations.Child

interface OneToOneRefEntity: WorkspaceEntity {
  val version: Int
  val text: String
  val anotherEntity: List<@Child com.intellij.platform.workspace.storage.testEntities.entities.currentVersion.AnotherOneToOneRefEntity> // Change is here, ONE_TO_ONE connection -> ONE_TO_MANY connection

  //region generated code
  @GeneratedCodeApiVersion(2)
  interface Builder : OneToOneRefEntity, WorkspaceEntity.Builder<OneToOneRefEntity> {
    override var entitySource: EntitySource
    override var version: Int
    override var text: String
    override var anotherEntity: List<AnotherOneToOneRefEntity>
  }

  companion object : EntityType<OneToOneRefEntity, Builder>() {
    @JvmOverloads
    @JvmStatic
    @JvmName("create")
    operator fun invoke(version: Int, text: String, entitySource: EntitySource, init: (Builder.() -> Unit)? = null): OneToOneRefEntity {
      val builder = builder()
      builder.version = version
      builder.text = text
      builder.entitySource = entitySource
      init?.invoke(builder)
      return builder
    }
  }
  //endregion
}

//region generated code
fun MutableEntityStorage.modifyEntity(entity: OneToOneRefEntity,
                                      modification: OneToOneRefEntity.Builder.() -> Unit): OneToOneRefEntity = modifyEntity(
  OneToOneRefEntity.Builder::class.java, entity, modification)
//endregion

interface AnotherOneToOneRefEntity: WorkspaceEntity {
  val someString: String
  val boolean: Boolean
  val parentEntity: com.intellij.platform.workspace.storage.testEntities.entities.currentVersion.OneToOneRefEntity

  //region generated code
  @GeneratedCodeApiVersion(2)
  interface Builder : AnotherOneToOneRefEntity, WorkspaceEntity.Builder<AnotherOneToOneRefEntity> {
    override var entitySource: EntitySource
    override var someString: String
    override var boolean: Boolean
    override var parentEntity: OneToOneRefEntity
  }

  companion object : EntityType<AnotherOneToOneRefEntity, Builder>() {
    @JvmOverloads
    @JvmStatic
    @JvmName("create")
    operator fun invoke(someString: String,
                        boolean: Boolean,
                        entitySource: EntitySource,
                        init: (Builder.() -> Unit)? = null): AnotherOneToOneRefEntity {
      val builder = builder()
      builder.someString = someString
      builder.boolean = boolean
      builder.entitySource = entitySource
      init?.invoke(builder)
      return builder
    }
  }
  //endregion
}

//region generated code
fun MutableEntityStorage.modifyEntity(entity: AnotherOneToOneRefEntity,
                                      modification: AnotherOneToOneRefEntity.Builder.() -> Unit): AnotherOneToOneRefEntity = modifyEntity(
  AnotherOneToOneRefEntity.Builder::class.java, entity, modification)
//endregion
