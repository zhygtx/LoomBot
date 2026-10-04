<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { Eye, EyeOff } from '@lucide/vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    label: string
    name: string
    type?: 'email' | 'password' | 'text'
    placeholder?: string
    autocomplete?: string
    error?: string
    hint?: string
    disabled?: boolean
    required?: boolean
    revealable?: boolean
    inputmode?: 'email' | 'numeric' | 'text'
    maxlength?: number
    list?: string
  }>(),
  {
    type: 'text',
    placeholder: '',
    autocomplete: 'off',
    error: '',
    hint: '',
    disabled: false,
    required: false,
    revealable: false,
    inputmode: 'text',
    maxlength: undefined,
    list: undefined,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  blur: []
}>()

const generatedId = useId()
const inputId = computed(() => `${props.name}-${generatedId}`)
const messageId = computed(() => `${inputId.value}-message`)
const passwordVisible = ref(false)
const resolvedType = computed(() => {
  if (props.revealable && props.type === 'password' && passwordVisible.value) return 'text'
  return props.type
})
</script>

<template>
  <div class="base-field">
    <div class="base-field__label-row">
      <label class="base-field__label" :for="inputId">
        <span class="base-field__label-text"
          ><slot name="label">{{ label }}</slot></span
        >
        <span v-if="required" class="base-field__required" aria-hidden="true">*</span>
      </label>
      <span v-if="$slots['label-extra']" class="base-field__label-extra">
        <slot name="label-extra" />
      </span>
    </div>

    <span class="base-field__control" :class="{ 'base-field__control--error': error }">
      <input
        :id="inputId"
        class="base-field__input"
        :name="name"
        :type="resolvedType"
        :value="modelValue"
        :placeholder="placeholder"
        :autocomplete="autocomplete"
        :disabled="disabled"
        :required="required"
        :inputmode="inputmode"
        :maxlength="maxlength"
        :list="list"
        :aria-invalid="Boolean(error)"
        :aria-describedby="error || hint ? messageId : undefined"
        @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
        @blur="emit('blur')"
      />
      <button
        v-if="revealable"
        class="base-field__reveal"
        type="button"
        :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
        :disabled="disabled"
        @click="passwordVisible = !passwordVisible"
      >
        <component :is="passwordVisible ? EyeOff : Eye" :size="18" aria-hidden="true" />
      </button>
      <slot name="suffix" />
    </span>

    <span
      :id="messageId"
      class="base-field__message"
      :class="{
        'base-field__message--error': error,
        'base-field__message--empty': !error && !hint,
      }"
      aria-live="polite"
    >
      {{ error || hint || '\u00a0' }}
    </span>
  </div>
</template>

<style scoped>
.base-field {
  display: grid;
  gap: var(--sys-space-2);
}

.base-field__label {
  display: inline-flex;
  min-inline-size: 0;
  align-items: center;
  color: var(--sys-color-text);
  font: var(--sys-typography-label-strong);
  gap: 0.2rem;
}

.base-field__label-row {
  display: flex;
  min-inline-size: 0;
  align-items: center;
  justify-content: space-between;
  gap: var(--sys-space-3);
}

.base-field__label-text {
  min-inline-size: 0;
}

.base-field__label-extra {
  flex: 0 0 auto;
  font: var(--sys-typography-caption);
}

.base-field__required {
  color: var(--sys-color-danger);
}

.base-field__control {
  display: flex;
  min-block-size: var(--cmp-field-height);
  align-items: center;
  border: var(--cmp-field-border-width) solid var(--sys-color-border-strong);
  border-radius: var(--cmp-field-radius);
  background: var(--sys-color-field);
  transition:
    border-color var(--sys-motion-fast),
    box-shadow var(--sys-motion-fast),
    background-color var(--sys-motion-fast);
}

.base-field__control:hover:not(:focus-within) {
  border-color: var(--sys-color-border-interactive);
}

.base-field__control:focus-within {
  border-color: var(--sys-color-action-primary);
  box-shadow: 0 0 0 var(--sys-focus-width) var(--sys-color-focus);
}

.base-field__control--error {
  border-color: var(--sys-color-danger);
}

.base-field__input {
  min-inline-size: 0;
  flex: 1;
  border: 0;
  outline: 0;
  padding: 0 var(--cmp-field-padding-inline);
  background: transparent;
  color: var(--sys-color-text);
  font: var(--sys-typography-body-compact);
}

.base-field__input::placeholder {
  color: var(--sys-color-text-subtle);
}

.base-field__input:disabled {
  cursor: not-allowed;
}

.base-field__control:has(.base-field__input:disabled) {
  background: var(--sys-color-surface-muted);
  opacity: var(--sys-opacity-disabled);
}

.base-field__reveal {
  display: grid;
  flex: 0 0 auto;
  inline-size: 2.75rem;
  block-size: 100%;
  place-items: center;
  align-self: stretch;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--sys-color-text-muted);
  cursor: pointer;
}

.base-field__reveal:hover {
  color: var(--sys-color-text-accent);
}

.base-field__message {
  min-block-size: 1.25rem;
  color: var(--sys-color-text-muted);
  font: var(--sys-typography-caption);
}

.base-field__message--error {
  color: var(--sys-color-danger);
}

.base-field__message--empty {
  visibility: hidden;
}

.base-field__input::-ms-reveal,
.base-field__input::-ms-clear {
  display: none;
}
</style>
